package org.citydb.terrain.provider;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import org.apache.tomcat.jdbc.pool.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Provides elevation data by querying 3D point geometries (PointZ) from a PostGIS database.
 * Points are snapped to a regular grid and averaged in SQL, then filled into the elevation grid.
 */
public class PointCloudElevationProvider implements ElevationProvider {

    private static final CoordinateReferenceSystem CRS_WGS84;

    static {
        try {
            CRS_WGS84 = CRS.decode("EPSG:4326");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String POINT_QUERY_TEMPLATE = """
            WITH snapped AS (
                SELECT ST_SnapToGrid(geom, ?, ?, ?, ?) AS sgeom, ST_Z(geom) AS z
                FROM %s
                WHERE geom && ST_MakeEnvelope(?, ?, ?, ?, %d)
            )
            SELECT ST_X(sgeom) AS sx, ST_Y(sgeom) AS sy, AVG(z) AS elevation
            FROM snapped
            GROUP BY sgeom""";

    private final DataSource dataSource;
    private final String tableName;
    private final int srid;
    private final CoordinateReferenceSystem pointCrs;
    private final MathTransform wgs84ToPointCrs;
    private final String pointQuery;

    public PointCloudElevationProvider(String url, String user, String password, String tableName) {
        this.tableName = tableName;
        this.dataSource = ElevationGridUtils.createDataSource(url, user, password);

        try {
            srid = querySrid();
            pointCrs = CRS.decode("EPSG:" + srid);
            wgs84ToPointCrs = CRS.findMathTransform(CRS_WGS84, pointCrs, true);
            pointQuery = POINT_QUERY_TEMPLATE.formatted(tableName, srid);
            System.out.println("Point cloud table: " + tableName + ", SRID: EPSG:" + srid);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize point cloud provider", e);
        }
    }

    private int querySrid() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT ST_SRID(geom) FROM " + tableName + " LIMIT 1")) {
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
        double[][] elevationData = new double[gridSize][gridSize];

        if (queryData) {
            fillFromPointCloud(bounds, gridSize, elevationData);
            elevationData = ElevationGridUtils.smoothGrid(elevationData);
        }

        ElevationGridUtils.applyEdgeCache(elevationData, gridSize, zoom, tileX, tileY, cacheMap);
        return elevationData;
    }

    private void fillFromPointCloud(double[] bounds, int gridSize, double[][] elevationData) {
        double minLon = bounds[0], maxLon = bounds[1], minLat = bounds[2], maxLat = bounds[3];

        try {
            ReferencedEnvelope bboxWGS84 = new ReferencedEnvelope(minLat, maxLat, minLon, maxLon, CRS_WGS84);
            ReferencedEnvelope bboxProj = bboxWGS84.transform(pointCrs, true);

            double projMinX = bboxProj.getMinX();
            double projMinY = bboxProj.getMinY();
            double projMaxX = bboxProj.getMaxX();
            double projMaxY = bboxProj.getMaxY();

            double cellW = (projMaxX - projMinX) / (gridSize - 1);
            double cellH = (projMaxY - projMinY) / (gridSize - 1);

            double xBuffer = (projMaxX - projMinX) * 0.1;
            double yBuffer = (projMaxY - projMinY) * 0.1;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(pointQuery)) {

                ps.setDouble(1, projMinX);
                ps.setDouble(2, projMinY);
                ps.setDouble(3, cellW);
                ps.setDouble(4, cellH);

                ps.setDouble(5, projMinX - xBuffer);
                ps.setDouble(6, projMinY - yBuffer);
                ps.setDouble(7, projMaxX + xBuffer);
                ps.setDouble(8, projMaxY + yBuffer);

                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    double sx = rs.getDouble("sx");
                    double sy = rs.getDouble("sy");
                    double elev = rs.getDouble("elevation");

                    int gx = (int) Math.round((sx - projMinX) / cellW);
                    int gy = (int) Math.round((sy - projMinY) / cellH);

                    if (gx >= 0 && gx < gridSize && gy >= 0 && gy < gridSize && elev > 0) {
                        elevationData[gx][gy] = elev;
                    }
                }
            }
        } catch (Throwable e) {
            // silently skip tiles with no point data
        }
    }
}
