package org.citydb.raster.provider;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Shared utilities for elevation grid post-processing and database setup.
 * Used by all ElevationProvider implementations.
 */
class ElevationGridUtils {

    static DataSource createDataSource(String url, String user, String password) {
        PoolProperties p = new PoolProperties();
        p.setUrl(url);
        p.setUsername(user);
        p.setPassword(password);
        p.setDriverClassName("org.postgresql.Driver");
        p.setMaxActive(Runtime.getRuntime().availableProcessors());
        p.setMaxIdle(Runtime.getRuntime().availableProcessors());
        p.setMinIdle(2);
        p.setInitialSize(2);
        p.setMaxWait(10000);
        p.setTestOnBorrow(true);
        p.setValidationQuery("SELECT 1");
        DataSource ds = new DataSource();
        ds.setPoolProperties(p);
        return ds;
    }

    static void applyEdgeCache(double[][] elevationData, int gridSize,
                                int zoom, int tileX, int tileY,
                                Map<String, double[]> cacheMap) {
        int gs = gridSize - 1;

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

    static double[][] smoothGrid(double[][] grid) {
        int rows = grid.length;
        int cols = grid[0].length;
        double[][] smoothed = new double[rows][cols];

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

                smoothed[i][j] = sum / count;
            }
        }

        return smoothed;
    }
}
