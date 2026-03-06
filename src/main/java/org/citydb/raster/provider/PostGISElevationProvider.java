package org.citydb.raster.provider;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Provides elevation data by fetching rasters from a PostGIS database
 * and sampling them into grids suitable for terrain tile generation.
 */
public class PostGISElevationProvider implements ElevationProvider {

    private static final CoordinateReferenceSystem CRS_WGS84;
    private static final CoordinateReferenceSystem CRS_UTM32;
    private static final MathTransform WGS84_TO_UTM32;

    static {
        try {
            CRS_WGS84 = CRS.decode("EPSG:4326");
            CRS_UTM32 = CRS.decode("EPSG:25832");
            WGS84_TO_UTM32 = CRS.findMathTransform(CRS_WGS84, CRS_UTM32, true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String RASTER_QUERY = """
            WITH bbox AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 25832) AS geom
            )
            SELECT ST_AsGDALRaster(ST_Union(ST_Resample(
                       r.rast,
                       ST_MakeEmptyRaster(
                           ?, ?,
                           ?, ?,
                           ?, ?,
                           0, 0,
                           25832
                       )
                   , 'Avg'), 'MAX'), 'GTiff') AS resampled_raster
            FROM raster_table r, bbox b
            WHERE ST_Intersects(r.rast, b.geom)""";

    private final DataSource dataSource;

    public PostGISElevationProvider() {
        PoolProperties p = new PoolProperties();
        p.setUrl("jdbc:postgresql://localhost:5432/bayern_dem_raster");
        p.setUsername("postgres");
        p.setPassword("125125");
        p.setDriverClassName("org.postgresql.Driver");
        p.setMaxActive(Runtime.getRuntime().availableProcessors());
        p.setMaxIdle(Runtime.getRuntime().availableProcessors());
        p.setMinIdle(2);
        p.setInitialSize(2);
        p.setMaxWait(10000);
        p.setTestOnBorrow(true);
        p.setValidationQuery("SELECT 1");
        dataSource = new DataSource();
        dataSource.setPoolProperties(p);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @Override
    public double[][] fetchElevationGrid(double[] bounds, int gridSize, int zoom, int tileX, int tileY,
                                          Map<String, double[]> cacheMap, boolean queryData) throws Exception {
        double minX = bounds[0], maxX = bounds[1], minY = bounds[2], maxY = bounds[3];
        double cellSizeX = (maxX - minX) / (gridSize - 1);
        double cellSizeY = (maxY - minY) / (gridSize - 1);

        double[][] elevationData = new double[gridSize][gridSize];

        if (queryData) {
            GridCoverage2D coverage = getGeoTiffFromDB(minX, minY, maxX, maxY, gridSize, gridSize);
            if (coverage != null) {
                sampleRasterToGrid(coverage, elevationData, gridSize, minX, minY, cellSizeX, cellSizeY);
                elevationData = smoothGrid(elevationData);
            }
        }

        applyEdgeCache(elevationData, gridSize, zoom, tileX, tileY, cacheMap);

        return elevationData;
    }

    private void sampleRasterToGrid(GridCoverage2D coverage, double[][] elevationData,
                                     int gridSize, double minX, double minY,
                                     double cellSizeX, double cellSizeY) throws Exception {
        int numPts = gridSize * gridSize;

        // Pre-compute all WGS84 coords as flat double[] (lat, lon pairs for EPSG:4326)
        double[] srcPts = new double[numPts * 2];
        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                int idx = (y * gridSize + x) * 2;
                srcPts[idx] = minY + cellSizeY * y;
                srcPts[idx + 1] = minX + cellSizeX * x;
            }
        }

        // Batch transform WGS84 -> UTM32
        double[] utmPts = new double[numPts * 2];
        WGS84_TO_UTM32.transform(srcPts, 0, utmPts, 0, numPts);

        // World-to-pixel transform
        GridGeometry2D gridGeometry = coverage.getGridGeometry();
        MathTransform crsToGrid = gridGeometry.getGridToCRS2D().inverse();

        double[] pixelPts = new double[numPts * 2];
        crsToGrid.transform(utmPts, 0, pixelPts, 0, numPts);

        // Read elevations directly from raster
        Raster raster = coverage.getRenderedImage().getData();
        int rasterW = raster.getWidth();
        int rasterH = raster.getHeight();
        int rasterMinX = raster.getMinX();
        int rasterMinY = raster.getMinY();

        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                int idx = (y * gridSize + x) * 2;
                int px = (int) Math.round(pixelPts[idx]);
                int py = (int) Math.round(pixelPts[idx + 1]);

                if (px >= rasterMinX && px < rasterMinX + rasterW
                        && py >= rasterMinY && py < rasterMinY + rasterH) {
                    double elev = raster.getSampleDouble(px, py, 0);
                    elevationData[x][y] = elev > 0 ? elev : 0;
                }
            }
        }
    }

    private static void applyEdgeCache(double[][] elevationData, int gridSize,
                                         int zoom, int tileX, int tileY,
                                         Map<String, double[]> cacheMap) {
        int gs = gridSize - 1;

        // Shared edges — whichever tile reaches the key first stores its values;
        // the adjacent tile then adopts them for seamless boundaries.
        cacheEdge(elevationData, gridSize, cacheMap,
                zoom + "_" + (tileX - 1) + "_" + tileY + "_" + tileX + "_" + tileY,
                i -> 0, i -> i);
        cacheEdge(elevationData, gridSize, cacheMap,
                zoom + "_" + tileX + "_" + tileY + "_" + (tileX + 1) + "_" + tileY,
                i -> gs, i -> i);
        cacheEdge(elevationData, gridSize, cacheMap,
                zoom + "_" + tileX + "_" + (tileY - 1) + "_" + tileX + "_" + tileY,
                i -> i, i -> 0);
        cacheEdge(elevationData, gridSize, cacheMap,
                zoom + "_" + tileX + "_" + tileY + "_" + tileX + "_" + (tileY + 1),
                i -> i, i -> gs);

        // Corner reconciliation: use separate corner keys so all tiles
        // meeting at a corner agree on the same elevation value.
        cacheCorner(elevationData, cacheMap, "c_" + zoom + "_" + tileX + "_" + tileY, 0, 0);
        cacheCorner(elevationData, cacheMap, "c_" + zoom + "_" + (tileX + 1) + "_" + tileY, gs, 0);
        cacheCorner(elevationData, cacheMap, "c_" + zoom + "_" + tileX + "_" + (tileY + 1), 0, gs);
        cacheCorner(elevationData, cacheMap, "c_" + zoom + "_" + (tileX + 1) + "_" + (tileY + 1), gs, gs);
    }

    private static void cacheEdge(double[][] data, int gridSize, Map<String, double[]> cache,
                                    String key, IntUnaryOperator xIdx, IntUnaryOperator yIdx) {
        double[] values = new double[gridSize];
        for (int i = 0; i < gridSize; i++) {
            values[i] = data[xIdx.applyAsInt(i)][yIdx.applyAsInt(i)];
        }
        double[] existing = cache.putIfAbsent(key, values);
        if (existing != null) {
            for (int i = 0; i < gridSize; i++) {
                data[xIdx.applyAsInt(i)][yIdx.applyAsInt(i)] = existing[i];
            }
        }
    }

    private static void cacheCorner(double[][] data, Map<String, double[]> cache,
                                     String key, int x, int y) {
        double[] val = {data[x][y]};
        double[] existing = cache.putIfAbsent(key, val);
        if (existing != null) {
            data[x][y] = existing[0];
        }
    }

    private GridCoverage2D getGeoTiffFromDB(double minLon, double minLat, double maxLon, double maxLat,
                                             int columns, int rows) {
        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(RASTER_QUERY)
        ) {
            ReferencedEnvelope bboxWGS84 = new ReferencedEnvelope(minLat, maxLat, minLon, maxLon, CRS_WGS84);
            ReferencedEnvelope bboxUTM32 = bboxWGS84.transform(CRS_UTM32, true);

            double xBuffer = bboxUTM32.getWidth() * 0.1;
            double yBuffer = bboxUTM32.getHeight() * 0.1;

            double xMin = bboxUTM32.getMinX() - xBuffer;
            double yMin = bboxUTM32.getMinY() - yBuffer;
            double xMax = bboxUTM32.getMaxX() + xBuffer;
            double yMax = bboxUTM32.getMaxY() + yBuffer;

            double xRes = (xMax - xMin) / columns;
            double yRes = (yMax - yMin) / rows;

            ps.setDouble(1, xMin);
            ps.setDouble(2, yMin);
            ps.setDouble(3, xMax);
            ps.setDouble(4, yMax);
            ps.setInt(5, columns);
            ps.setInt(6, rows);
            ps.setDouble(7, xMin);
            ps.setDouble(8, yMax);
            ps.setDouble(9, xRes);
            ps.setDouble(10, yRes);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                byte[] rasterData = rs.getBytes(1);
                GeoTiffReader reader = new GeoTiffReader(new ByteArrayInputStream(rasterData));
                return reader.read();
            }
        } catch (Throwable e) {
            // silently skip tiles with no raster data
        }

        return null;
    }

    static double[][] smoothGrid(double[][] grid) {
        int rows = grid.length;
        int cols = grid[0].length;
        double[][] smoothedGrid = new double[rows][cols];

        int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double sum = grid[i][j];
                int count = 1;

                for (int k = 0; k < 8; k++) {
                    int ni = i + dx[k];
                    int nj = j + dy[k];

                    if (ni >= 0 && ni < rows && nj >= 0 && nj < cols) {
                        sum += grid[ni][nj];
                        count++;
                    }
                }

                smoothedGrid[i][j] = sum / count;
            }
        }

        return smoothedGrid;
    }
}
