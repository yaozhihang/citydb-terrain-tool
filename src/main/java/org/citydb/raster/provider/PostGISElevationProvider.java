package org.citydb.raster.provider;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import org.apache.tomcat.jdbc.pool.DataSource;

import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Provides elevation data by fetching rasters from a PostGIS database
 * and sampling them into grids suitable for terrain tile generation.
 */
public class PostGISElevationProvider implements ElevationProvider {

    private static final CoordinateReferenceSystem CRS_WGS84;

    static {
        try {
            CRS_WGS84 = CRS.decode("EPSG:4326");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String RASTER_QUERY_TEMPLATE = """
            WITH bbox AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, %d) AS geom
            )
            SELECT ST_AsGDALRaster(ST_Union(ST_Resample(
                       r.rast,
                       ST_MakeEmptyRaster(
                           ?, ?,
                           ?, ?,
                           ?, ?,
                           0, 0,
                           %d
                       )
                   , 'Avg'), 'MAX'), 'GTiff') AS resampled_raster
            FROM %s r, bbox b
            WHERE ST_Intersects(r.rast, b.geom)""";

    private final DataSource dataSource;
    private final String tableName;
    private final int srid;
    private final CoordinateReferenceSystem rasterCrs;
    private final MathTransform wgs84ToRasterCrs;
    private final String rasterQuery;

    public PostGISElevationProvider(String url, String user, String password, String tableName) {
        this.tableName = tableName;
        this.dataSource = ElevationGridUtils.createDataSource(url, user, password);

        try {
            srid = queryRasterSrid();
            rasterCrs = CRS.decode("EPSG:" + srid);
            wgs84ToRasterCrs = CRS.findMathTransform(CRS_WGS84, rasterCrs, true);
            rasterQuery = RASTER_QUERY_TEMPLATE.formatted(srid, srid, tableName);
            System.out.println("Raster table: " + tableName + ", SRID: EPSG:" + srid);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize elevation provider", e);
        }
    }

    private int queryRasterSrid() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT ST_SRID(rast) FROM " + tableName + " LIMIT 1")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            throw new IllegalStateException(tableName + " is empty, cannot determine SRID");
        }
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
                elevationData = ElevationGridUtils.smoothGrid(elevationData);
            }
        }

        ElevationGridUtils.applyEdgeCache(elevationData, gridSize, zoom, tileX, tileY, cacheMap);

        return elevationData;
    }

    private void sampleRasterToGrid(GridCoverage2D coverage, double[][] elevationData,
                                     int gridSize, double minX, double minY,
                                     double cellSizeX, double cellSizeY) throws Exception {
        int numPts = gridSize * gridSize;

        double[] srcPts = new double[numPts * 2];
        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                int idx = (y * gridSize + x) * 2;
                srcPts[idx] = minY + cellSizeY * y;
                srcPts[idx + 1] = minX + cellSizeX * x;
            }
        }

        double[] projPts = new double[numPts * 2];
        wgs84ToRasterCrs.transform(srcPts, 0, projPts, 0, numPts);

        GridGeometry2D gridGeometry = coverage.getGridGeometry();
        MathTransform crsToGrid = gridGeometry.getGridToCRS2D().inverse();

        double[] pixelPts = new double[numPts * 2];
        crsToGrid.transform(projPts, 0, pixelPts, 0, numPts);

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

    private GridCoverage2D getGeoTiffFromDB(double minLon, double minLat, double maxLon, double maxLat,
                                             int columns, int rows) {
        try (
                Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(rasterQuery)
        ) {
            ReferencedEnvelope bboxWGS84 = new ReferencedEnvelope(minLat, maxLat, minLon, maxLon, CRS_WGS84);
            ReferencedEnvelope bboxProj = bboxWGS84.transform(rasterCrs, true);

            double xBuffer = bboxProj.getWidth() * 0.1;
            double yBuffer = bboxProj.getHeight() * 0.1;

            double xMin = bboxProj.getMinX() - xBuffer;
            double yMin = bboxProj.getMinY() - yBuffer;
            double xMax = bboxProj.getMaxX() + xBuffer;
            double yMax = bboxProj.getMaxY() + yBuffer;

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
}
