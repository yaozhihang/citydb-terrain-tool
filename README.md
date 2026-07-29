# CityDB Terrain Tool

Generates [Cesium quantized-mesh](https://github.com/CesiumGS/quantized-mesh) terrain tiles from PostGIS raster elevation data. Designed for serving 3D terrain in CesiumJS-based viewers.

## Architecture

```
org.citydb.terrain
└── Launcher                    – CLI entry point, dispatches subcommands

org.citydb.terrain.operation
├── TerrainImporter             – Imports XYZ data into PostGIS (multi-threaded)
└── QMSGenerator                – Orchestrates multi-zoom tile generation

org.citydb.terrain.mesh
├── MeshStrategy                – Interface for mesh generation algorithms
├── MeshResult                  – Mesh output (vertices, triangles, edges)
├── RtinMesh                    – RTIN adaptive mesh generator
├── DelaunayMesh                – Delaunay adaptive mesh generator
└── SimpleGridMesh              – Regular-grid mesh (no simplification)

org.citydb.terrain.tile
├── TerrainTileCreator          – Per-tile pipeline (elevation → mesh → binary)
└── TerrainTileWriter           – Encodes mesh into Cesium .terrain format

org.citydb.terrain.provider
├── ElevationProvider           – Interface for elevation data sources
├── PostGISElevationProvider    – Fetches elevation from PostGIS raster data
└── ElevationGridUtils          – Shared grid utilities (edge cache, smoothing)

org.citydb.terrain.util
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
- Seeds edges and interior at regular intervals, then inserts vertices by error
- Greedily inserts the grid point with the highest interpolation error
- Produces well-shaped triangles (maximizes minimum angle)

### SimpleGridMesh

Uniform regular-grid triangulation. Every grid cell is split into two triangles with no adaptive simplification.

- **Any grid size** (no constraints)
- All grid points become vertices — produces a dense, uniform mesh
- `maxError` and `maxTriangleSpan` parameters are ignored
- Useful when consistent mesh density is preferred over adaptive simplification

## Elevation Provider

Elevation is read through the `ElevationProvider` interface. The only implementation is `PostGISElevationProvider`, which fetches raster elevation data from PostGIS using `ST_Resample` and `ST_Union`. SRID is auto-detected from the database, and the tile extent defaults to the raster's own extent.

There is no runtime provider selection — the raster provider is used unconditionally. Adding another source means implementing `ElevationProvider` and wiring it up in `Launcher`.

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
| 13   | **513**  | 512        | **~4.8**      | ~33k            | **Matches 5m raster**    |
| 14   | 129      | 128        | ~9.5          | ~130k           | Slightly coarser than 5m |
| 15   | 129      | 128        | ~4.8          | ~530k           | Matches 5m raster        |

### Constraints

- **RTIN** requires `gridSize = 2^n + 1`: valid values are 3, 5, 9, 17, 33, 65, 129, 257, 513, 1025...
- **Delaunay** and **Simple** accept any grid size
- **Quantized-mesh format** uses 16-bit vertex indices: max **65,535 vertices** per tile. With adaptive meshing this is rarely hit, but avoid `maxError = 0` with large grid sizes.

## CLI Parameters

The tool is split into two subcommands:

```
Usage: terrain-tool <command> [options]

Commands:
  import    Import XYZ terrain data (ZIP files) into database
  generate  Generate quantized mesh terrain tiles from database

Use 'terrain-tool <command> --help' for command-specific options.
```

### Database options (both commands)

| Option                | Description          | Default    |
|-----------------------|----------------------|------------|
| `-H, --host <host>`   | Database host        | *required* |
| `-P, --port <n>`      | Database port        | `5432`     |
| `-d, --db <name>`     | Database name        | *required* |
| `-u, --user <name>`   | Database username    | *required* |
| `--password <pw>`     | Database password    | *required* |
| `-s, --schema <name>` | Database schema      | `public`   |
| `-t, --table <name>`  | Database table name  | *required* |

`-d` takes a plain database *name*, not a JDBC URL — the connection string is assembled internally as `jdbc:postgresql://<host>:<port>/<db>`. The table is addressed schema-qualified as `<schema>.<table>`.

### `import` options

| Option              | Description                           | Default    |
|---------------------|---------------------------------------|------------|
| `-i, --input <dir>` | Input folder containing XYZ ZIP files | *required* |
| `-h, --help`        | Show help message                     |            |

### `generate` options

| Option               | Description                       | Default            |
|----------------------|-----------------------------------|--------------------|
| `--minX <lon>`       | West extent longitude             | from raster extent |
| `--maxX <lon>`       | East extent longitude             | from raster extent |
| `--minY <lat>`       | South extent latitude             | from raster extent |
| `--maxY <lat>`       | North extent latitude             | from raster extent |
| `--gridSize <n>`     | Grid size, must be 2^n+1 for RTIN | `33`               |
| `-z, --zoom <n>`     | Max zoom level                    | `10`               |
| `-e, --error <m>`    | Base error in meters              | `5.0`              |
| `-o, --output <dir>` | Output folder                     | `viewer/terrain/`  |
| `-m, --mesh <type>`  | Mesh strategy                     | `delaunay`         |
| `-h, --help`         | Show help message                 |                    |

When an extent option is omitted, the bounding box is queried from the raster table itself, so `generate` covers the full dataset by default.

Available mesh strategies for `--mesh`:

| Value      | Strategy       | Description                               |
|------------|----------------|-------------------------------------------|
| `delaunay` | DelaunayMesh   | Adaptive Delaunay triangulation (default) |
| `rtin`     | RtinMesh       | Adaptive RTIN, requires gridSize = 2^n+1  |
| `simple`   | SimpleGridMesh | Uniform grid, no adaptive simplification  |

An unrecognised value prints a warning and falls back to `delaunay`.

### Examples

```bash
# Import XYZ ZIP archives into PostGIS
gradle run --args="import -H localhost -d mydb -u postgres --password YOUR_PASSWORD -t raster_table -i /path/to/xyz"

# Generate tiles over the full raster extent (Delaunay mesh, zoom 10)
gradle run --args="generate -H localhost -d mydb -u postgres --password YOUR_PASSWORD -t raster_table"

# Custom extent with the RTIN strategy
gradle run --args="generate -H localhost -d mydb -u postgres --password YOUR_PASSWORD -t raster_table --minX 10.7078 --maxX 10.8926 --minY 47.5541 --maxY 47.6156 --mesh rtin --gridSize 129 --zoom 12"

# Simple grid mesh with higher zoom and tighter error
gradle run --args="generate -H localhost -d mydb -u postgres --password YOUR_PASSWORD -t raster_table --mesh simple --zoom 14 --error 2.0 --output output/terrain/"
```

## Building & Running

Requires Java 21+ and a PostGIS database holding raster elevation data.

### Development (Gradle)

```bash
./gradlew run --args="--help"            # Linux/Mac
gradlew.bat run --args="--help"          # Windows
```

### Distribution build (recommended)

```bash
./gradlew installDist                    # Linux/Mac
gradlew.bat installDist                  # Windows

# Create a distributable zip / tar
./gradlew distZip
./gradlew distTar
```

The build produces a self-contained distribution under `build/install/citydb-terrain-tool/`:

```
citydb-terrain-tool/
├── bin/         Start scripts (POSIX + .bat)
└── lib/         All JARs (project + dependencies)
```

Run the installed distribution directly:

```bash
build/install/citydb-terrain-tool/bin/citydb-terrain-tool --help
build/install/citydb-terrain-tool/bin/citydb-terrain-tool generate --help
```

> Subcommand help still prints the full option list, but because the required database options are validated before `--help` is handled, it is preceded by a `Missing required options` message and exits with a non-zero status.

The start scripts pull dependencies via the project JAR's `Class-Path` manifest entry, so the `CLASSPATH` variable stays short — important on Windows, where long classpaths can exceed the command-line length limit.

### Output layout

Output is written to the configured output folder in TMS layout:

```
viewer/terrain/
├── layer.json
├── 0/0/0.terrain
├── 1/0/0.terrain
├── ...
└── {z}/{x}/{y}.terrain
```

## Docker

A multi-stage `Dockerfile` is provided. Stage 1 builds the distribution with the Gradle wrapper; stage 2 produces a minimal `eclipse-temurin:21-jre` runtime image.

```bash
# Build
docker build -t citydb-terrain-tool .

# Show help
docker run --rm citydb-terrain-tool --help
```

### Importing XYZ data into PostGIS

```bash
docker run --rm \
  --network host \
  -v /path/to/xyz:/data/input \
  citydb-terrain-tool import \
    -H localhost -d mydb -u postgres --password YOUR_PASSWORD \
    -t raster_table -i /data/input
```

### Generating quantized-mesh tiles

```bash
docker run --rm \
  --network host \
  -v /path/to/output:/data/output \
  citydb-terrain-tool generate \
    -H localhost -d mydb -u postgres --password YOUR_PASSWORD \
    -t raster_table -o /data/output -z 12 -m delaunay
```

### Windows examples

PowerShell:
```powershell
docker run --rm `
  -v "${PWD}\output:/data/output" `
  citydb-terrain-tool generate `
    -H host.docker.internal -d mydb -u postgres --password YOUR_PASSWORD `
    -t raster_table -o /data/output -z 12
```

CMD:
```cmd
docker run --rm ^
  -v "%cd%\output:/data/output" ^
  citydb-terrain-tool generate ^
    -H host.docker.internal -d mydb -u postgres --password YOUR_PASSWORD ^
    -t raster_table -o /data/output -z 12
```

> When the database runs on the Docker host, use `--network host` (Linux) or `host.docker.internal` (Windows/Mac) so the container can reach it.

> **Note on credentials:** passing `--password` on the command line exposes it to your shell history and to other users via the process list. Prefer a PostgreSQL [password file](https://www.postgresql.org/docs/current/libpq-pgpass.html) (`~/.pgpass`), or read the value from a variable that you keep outside version control.

## License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) for details.
