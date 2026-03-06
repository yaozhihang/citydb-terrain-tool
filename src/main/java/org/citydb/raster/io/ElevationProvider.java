package org.citydb.raster.io;

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

/**
 * Provides elevation data by fetching rasters from a PostGIS database
 * and sampling them into grids suitable for terrain tile generation.
 */
public class ElevationProvider {

    // Cached CRS and transform (decoded once at class load)
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

    private final DataSource dataSource;

    public ElevationProvider() {
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

    public void close() {
        dataSource.close();
    }

    /**
     * Fetch and prepare a complete elevation grid for a tile.
     * Combines raster sampling, smoothing, and edge caching into one call.
     *
     * @param bounds    tile bounds [minLon, maxLon, minLat, maxLat]
     * @param gridSize  side length of the square grid
     * @param zoom      zoom level (used for edge cache keys)
     * @param tileX     tile X coordinate (used for edge cache keys)
     * @param tileY     tile Y coordinate (used for edge cache keys)
     * @param cacheMap  shared edge cache for seamless tile boundaries
     * @param doStart   whether to actually fetch raster data (false = all zeros)
     * @return filled elevation grid [x][y]
     */
    public double[][] fetchElevationGrid(double[] bounds, int gridSize, int zoom, int tileX, int tileY,
                                          Map<String, double[]> cacheMap, boolean doStart) throws Exception {
        double minX = bounds[0];
        double maxX = bounds[1];
        double minY = bounds[2];
        double maxY = bounds[3];
        double cellSizeX = (maxX - minX) / (gridSize - 1);
        double cellSizeY = (maxY - minY) / (gridSize - 1);

        double[][] elevationData = new double[gridSize][gridSize];

        if (doStart) {
            GridCoverage2D coverage = getGeoTiffFromDB(minX, minY, maxX, maxY, gridSize, gridSize);
            if (coverage != null) {
                sampleRasterToGrid(coverage, elevationData, gridSize, minX, minY, cellSizeX, cellSizeY);
                elevationData = smoothGrid(elevationData);
            }
        }

        // Always apply edge cache — even flat/empty tiles must participate
        // so they can adopt neighbor edge values and vice versa
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
                srcPts[idx] = minY + cellSizeY * y;     // latitude
                srcPts[idx + 1] = minX + cellSizeX * x; // longitude
            }
        }

        // Batch transform WGS84 -> UTM32
        double[] utmPts = new double[numPts * 2];
        WGS84_TO_UTM32.transform(srcPts, 0, utmPts, 0, numPts);

        // Get world-to-pixel transform from coverage
        GridGeometry2D gridGeometry = coverage.getGridGeometry();
        MathTransform gridToCRS = gridGeometry.getGridToCRS2D();
        MathTransform crsToGrid = gridToCRS.inverse();

        // Batch transform UTM coords to pixel coords
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
                } else {
                    elevationData[x][y] = 0;
                }
            }
        }
    }

    private static void applyEdgeCache(double[][] elevationData, int gridSize,
                                         int zoom, int tileX, int tileY,
                                         Map<String, double[]> cacheMap) {
        // Use putIfAbsent for thread-safe edge caching.
        // Whichever tile reaches the shared edge key first stores its values;
        // the adjacent tile then adopts those values, ensuring both tiles
        // have identical elevations on their shared edge.

        // Left edge
        String keyLeft = zoom + "_" + (tileX - 1) + "_" + tileY + "_" + tileX + "_" + tileY;
        double[] leftElevation = new double[gridSize];
        for (int y = 0; y < gridSize; y++) {
            leftElevation[y] = elevationData[0][y];
        }
        double[] existingLeft = cacheMap.putIfAbsent(keyLeft, leftElevation);
        if (existingLeft != null) {
            for (int y = 0; y < gridSize; y++) {
                elevationData[0][y] = existingLeft[y];
            }
        }

        // Right edge
        String keyRight = zoom + "_" + tileX + "_" + tileY + "_" + (tileX + 1) + "_" + tileY;
        double[] rightElevation = new double[gridSize];
        for (int y = 0; y < gridSize; y++) {
            rightElevation[y] = elevationData[gridSize - 1][y];
        }
        double[] existingRight = cacheMap.putIfAbsent(keyRight, rightElevation);
        if (existingRight != null) {
            for (int y = 0; y < gridSize; y++) {
                elevationData[gridSize - 1][y] = existingRight[y];
            }
        }

        // Bottom edge
        String keyBottom = zoom + "_" + tileX + "_" + (tileY - 1) + "_" + tileX + "_" + tileY;
        double[] bottomElevation = new double[gridSize];
        for (int x = 0; x < gridSize; x++) {
            bottomElevation[x] = elevationData[x][0];
        }
        double[] existingBottom = cacheMap.putIfAbsent(keyBottom, bottomElevation);
        if (existingBottom != null) {
            for (int x = 0; x < gridSize; x++) {
                elevationData[x][0] = existingBottom[x];
            }
        }

        // Top edge
        String keyTop = zoom + "_" + tileX + "_" + tileY + "_" + tileX + "_" + (tileY + 1);
        double[] topElevation = new double[gridSize];
        for (int x = 0; x < gridSize; x++) {
            topElevation[x] = elevationData[x][gridSize - 1];
        }
        double[] existingTop = cacheMap.putIfAbsent(keyTop, topElevation);
        if (existingTop != null) {
            for (int x = 0; x < gridSize; x++) {
                elevationData[x][gridSize - 1] = existingTop[x];
            }
        }

        // Corner reconciliation: edges applied above may have left stale corner
        // values in the cache. Use separate corner keys so all tiles meeting at
        // a corner agree on the final elevation value.
        int gs = gridSize - 1;

        // Bottom-left corner (0,0)
        String cBL = "c_" + zoom + "_" + tileX + "_" + tileY;
        double[] blVal = {elevationData[0][0]};
        double[] exBL = cacheMap.putIfAbsent(cBL, blVal);
        if (exBL != null) elevationData[0][0] = exBL[0];

        // Bottom-right corner (gs,0)
        String cBR = "c_" + zoom + "_" + (tileX + 1) + "_" + tileY;
        double[] brVal = {elevationData[gs][0]};
        double[] exBR = cacheMap.putIfAbsent(cBR, brVal);
        if (exBR != null) elevationData[gs][0] = exBR[0];

        // Top-left corner (0,gs)
        String cTL = "c_" + zoom + "_" + tileX + "_" + (tileY + 1);
        double[] tlVal = {elevationData[0][gs]};
        double[] exTL = cacheMap.putIfAbsent(cTL, tlVal);
        if (exTL != null) elevationData[0][gs] = exTL[0];

        // Top-right corner (gs,gs)
        String cTR = "c_" + zoom + "_" + (tileX + 1) + "_" + (tileY + 1);
        double[] trVal = {elevationData[gs][gs]};
        double[] exTR = cacheMap.putIfAbsent(cTR, trVal);
        if (exTR != null) elevationData[gs][gs] = exTR[0];
    }

    private GridCoverage2D getGeoTiffFromDB(double minLon, double minLat, double maxLon, double maxLat,
                                             int columns, int rows) {
        GridCoverage2D coverage = null;
        byte[] rasterData;

        String query = "WITH bbox AS (\n" +
                "    SELECT ST_MakeEnvelope(?, ?, ?, ?, 25832) AS geom\n" +
                ")\n" +
                "SELECT ST_AsGDALRaster(ST_Union(ST_Resample(\n" +
                "           r.rast,\n" +
                "           ST_MakeEmptyRaster(\n" +
                "               ?, ?,\n" +
                "               ?, ?,\n" +
                "               ?, ?,\n" +
                "               0, 0,\n" +
                "               25832\n" +
                "           )\n" +
                "       , 'Avg'), 'MAX'), 'GTiff') AS resampled_raster\n" +
                "FROM raster_table r, bbox b\n" +
                "WHERE ST_Intersects(r.rast, b.geom)";

        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)
        ) {
            ReferencedEnvelope bboxWGS84 = new ReferencedEnvelope(minLat, maxLat, minLon, maxLon, CRS_WGS84);
            ReferencedEnvelope bboxUTM32 = bboxWGS84.transform(CRS_UTM32, true);

            double xBuffer = bboxUTM32.getWidth() / 2;
            double yBuffer = bboxUTM32.getHeight() / 2;

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
            ps.setInt(5, columns * 2);
            ps.setInt(6, rows * 2);
            ps.setDouble(7, xMin);
            ps.setDouble(8, yMax);
            ps.setDouble(9, xRes);
            ps.setDouble(10, yRes);

            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                rasterData = rs.getBytes(1);
                GeoTiffReader reader = new GeoTiffReader(new ByteArrayInputStream(rasterData));
                coverage = reader.read(null);
            } else {
                System.out.println("No raster found for the specified ID.");
            }
        } catch (Throwable e) {
            // silently skip tiles with no raster data
        }

        return coverage;
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
