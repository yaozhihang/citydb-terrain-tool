# CityDB Raster Tool

Generates [Cesium quantized-mesh](https://github.com/CesiumGS/quantized-mesh) terrain tiles from PostGIS raster elevation data. Designed for serving 3D terrain in CesiumJS-based viewers.

## Architecture

```
org.citydb.raster
└── TerrainTileApp              – CLI entry point, parses arguments

org.citydb.raster.mesh
├── MeshStrategy                – Interface for mesh generation algorithms
├── MeshResult                  – Mesh output (vertices, triangles, edges)
├── RtinMesh                    – RTIN adaptive mesh generator
├── DelaunayMesh                – Delaunay adaptive mesh generator
└── SimpleGridMesh              – Regular-grid mesh (no simplification)

org.citydb.raster.io
├── TerrainTileGenerator        – Orchestrates multi-zoom tile generation
├── GeoTiffToQuantizedMesh      – Per-tile pipeline (elevation → mesh → binary)
├── QuantizedMeshWriter         – Encodes mesh into Cesium .terrain format
├── ElevationProvider           – Fetches elevation from PostGIS
├── RasterImporter              – Imports GeoTIFF files into PostGIS
└── XYZToGeoTIFF                – Converts XYZ point files to GeoTIFF

org.citydb.raster.util
├── CoordinateUtils             – WGS84/ECEF/TMS coordinate conversions
├── BoundingSphere              – Bounding sphere computation
└── Cartesian3                  – 3D vector math, horizon culling
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

### SimpleGridMesh

Uniform regular-grid triangulation. Every grid cell is split into two triangles with no adaptive simplification.

- **Any grid size** (no constraints)
- All grid points become vertices — produces a dense, uniform mesh
- `maxError` and `maxTriangleSpan` parameters are ignored
- Useful when consistent mesh density is preferred over adaptive simplification

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
- **Delaunay** and **Simple** accept any grid size
- **Quantized-mesh format** uses 16-bit vertex indices: max **65,535 vertices** per tile. With adaptive meshing this is rarely hit, but avoid `maxError = 0` with large grid sizes.

## CLI Parameters

All parameters have sensible defaults and can be overridden via command-line options:

```
Usage: TerrainTileApp [options]

Options:
  --minX <lon>      West extent longitude             (default: 8.97205)
  --maxX <lon>      East extent longitude             (default: 13.84636)
  --minY <lat>      South extent latitude             (default: 47.26887)
  --maxY <lat>      North extent latitude             (default: 50.56651)
  --gridSize <n>    Grid size, must be 2^n+1 for RTIN (default: 33)
  --zoom <n>        Max zoom level                    (default: 10)
  --error <m>       Base error in meters              (default: 5.0)
  --output <dir>    Output folder                     (default: viewer/terrain/)
  --mesh <type>     Mesh strategy                     (default: delaunay)
  -h, --help        Show help message
```

Available mesh strategies for `--mesh`:

| Value      | Strategy        | Description                                     |
|------------|----------------|-------------------------------------------------|
| `delaunay` | DelaunayMesh   | Adaptive Delaunay triangulation (default)        |
| `rtin`     | RtinMesh       | Adaptive RTIN, requires gridSize = 2^n+1         |
| `simple`   | SimpleGridMesh | Uniform grid, no adaptive simplification         |

### Examples

```bash
# Run with defaults (Bavaria extent, Delaunay mesh, zoom 10)
gradle run

# Custom extent with RTIN strategy
gradle run --args="--minX 10.7078 --maxX 10.8926 --minY 47.5541 --maxY 47.6156 --mesh rtin --zoom 12"

# Simple grid mesh with higher zoom and tighter error
gradle run --args="--mesh simple --zoom 14 --error 2.0 --output output/terrain/"
```

## Building & Running

Requires Java 21+ and a PostGIS database with raster elevation data.

Configure the database connection in `ElevationProvider.java`, then run:

```bash
gradle run
gradle run --args="--help"
```

Output is written to the configured output folder in TMS layout:

```
viewer/terrain/
├── layer.json
├── 0/0/0.terrain
├── 1/0/0.terrain
├── ...
└── {z}/{x}/{y}.terrain
```
