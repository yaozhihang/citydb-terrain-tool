# CityDB Raster Tool

Generates [Cesium quantized-mesh](https://github.com/CesiumGS/quantized-mesh) terrain tiles from PostGIS raster elevation data. Designed for serving 3D terrain in CesiumJS-based viewers.

## Architecture

```
TerrainTileApp           – Entry point, configures zoom levels and tile generation
ElevationProvider        – Fetches raster elevation from PostGIS, handles edge caching
GeoTiffToQuantizedMesh   – Orchestrates per-tile pipeline (elevation → mesh → binary)
MeshStrategy             – Interface for mesh generation algorithms
├── RtinMesh             – RTIN (Right-Triangulated Irregular Network) mesh generator
└── DelaunayMesh         – Delaunay triangulation-based mesh generator
QuantizedMeshWriter      – Encodes mesh into Cesium's binary .terrain format
CoordinateUtils          – WGS84/ECEF/TMS coordinate conversions
```

## Mesh Strategies

### RtinMesh

Binary triangle tree approach similar to [mapbox/martini](https://github.com/mapbox/martini). Recursively bisects triangles at hypotenuse midpoints where interpolation error exceeds the threshold.

- **Grid size must be 2^n + 1** (e.g., 33, 65, 129, 257, 513)
- Fast error computation with bottom-up propagation
- Iterative convergence loop ensures diamond-pair consistency (no T-junction gaps)
- Tile-edge midpoints are force-split for seamless stitching

### DelaunayMesh

Bowyer-Watson incremental Delaunay triangulation with greedy vertex insertion.

- **Any grid size** (no power-of-two constraint)
- Starts with all edge vertices + regular interior seed grid
- Greedily inserts the grid point with the highest interpolation error
- Produces well-shaped triangles (maximizes minimum angle)

## Zoom Level & Grid Size Selection

The maximum useful zoom level depends on source raster resolution and the grid size per tile.

**Formula:**

```
cell_size ≈ 180 × 111,000 / (grid_cells × 2^z)   meters  (at mid-latitudes)
```

where `grid_cells = gridSize - 1`.

### Reference table for 5m raster (at ~49° latitude)

| Zoom | gridSize | Grid cells | Cell size (m) | Tiles (Bavaria) | Notes                    |
|------|----------|------------|---------------|-----------------|--------------------------|
| 10   | 129      | 128        | ~153          | ~500            | Very coarse overview     |
| 12   | 129      | 128        | ~38           | ~8k             | Overview                 |
| 13   | 129      | 128        | ~19           | ~33k            | Moderate                 |
| 13   | **513**  | 512        | **~4.8**      | ~33k            | **Matches 5m raster**   |
| 14   | 129      | 128        | ~9.5          | ~130k           | Slightly coarser than 5m |
| 15   | 129      | 128        | ~4.8          | ~530k           | Matches 5m raster        |

### Recommended configurations for 5m raster

**Option A — Fewer tiles, denser grid (recommended):**

```java
int zoomLevel = 13;
int currentGridSize = (t == zoomLevel) ? 513 : 33;  // 2^9+1 at max zoom
```

- ~33k tiles, ~4.8m cell size at max zoom
- Fewer DB queries, less I/O overhead
- Requires `gridSize = 2^n+1` for RTIN, or use DelaunayMesh for arbitrary sizes

**Option B — More tiles, standard grid:**

```java
int zoomLevel = 15;
int currentGridSize = (t == zoomLevel) ? 129 : 33;  // 2^7+1 at max zoom
```

- ~530k tiles, ~4.8m cell size at max zoom
- Smaller individual tiles, higher total count

### Constraints

- **RTIN** requires `gridSize = 2^n + 1`: valid values are 3, 5, 9, 17, 33, 65, 129, 257, 513, 1025...
- **Delaunay** accepts any grid size
- **Quantized-mesh format** uses 16-bit vertex indices: max **65,535 vertices** per tile. With adaptive meshing this is rarely hit, but avoid `maxError = 0` with large grid sizes.

## Configuration Parameters

| Parameter          | Description                                                     |
|--------------------|-----------------------------------------------------------------|
| `zoomLevel`        | Maximum zoom level to generate                                  |
| `gridSize`         | Grid side length at normal zoom levels (default: 33)            |
| `baseError`        | Max interpolation error in meters at max zoom (default: 5.0)    |
| `maxTriangleSpan`  | Max grid cells a triangle edge may span (controls min density)  |
| `outputFolder`     | Output directory for .terrain files and layer.json              |

## Building & Running

Requires Java 11+ and a PostGIS database with raster elevation data.

Configure the database connection in `ElevationProvider.java`, then run `TerrainTileApp.main()`.

Output is written to the configured `outputFolder` in TMS layout:

```
viewer/terrain/
├── layer.json
├── 0/0/0.terrain
├── 1/0/0.terrain
├── ...
└── {z}/{x}/{y}.terrain
```
