package org.citydb.terrain.tile;

import org.citydb.terrain.mesh.MeshResult;
import org.citydb.terrain.mesh.MeshStrategy;
import org.citydb.terrain.provider.ElevationProvider;
import org.citydb.terrain.util.BoundingSphere;
import org.citydb.terrain.util.Cartesian3;
import org.citydb.terrain.util.CoordinateUtils;

import java.io.File;
import java.util.List;
import java.util.Map;

public class TerrainTileCreator {

    public static void createTMSTile(ElevationProvider provider, MeshStrategy meshStrategy,
                                     String outputFolder, int zoom, int tileX, int tileY,
                                     int gridSize, float maxError, int maxTriangleSpan,
                                     Map<String, double[]> cacheMap, double[] dataExtent,
                                     int skipDbZoom) throws Exception {
        double[] bounds = CoordinateUtils.calculateTileBounds(tileX, tileY, zoom);
        double minX = bounds[0], maxX = bounds[1], minY = bounds[2], maxY = bounds[3];

        boolean queryDb = zoom > skipDbZoom
                && minX < dataExtent[1] && maxX > dataExtent[0]
                && minY < dataExtent[3] && maxY > dataExtent[2];

        double[][] elevationData = provider.fetchElevationGrid(bounds, gridSize, zoom, tileX, tileY, cacheMap, queryDb);

        // Compute height range
        double minHeight = Double.MAX_VALUE, maxHeight = Double.MIN_VALUE;
        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                double elevation = elevationData[x][y];
                minHeight = Math.min(minHeight, elevation);
                maxHeight = Math.max(maxHeight, elevation);
            }
        }
        double avgHeight = (minHeight + maxHeight) / 2;

        // ECEF coordinates for tile center and corners
        double[] center = CoordinateUtils.convertWGSToECEF((minY + maxY) / 2, (minX + maxX) / 2, avgHeight);
        double[] corner1 = CoordinateUtils.convertWGSToECEF(minY, minX, avgHeight);
        double[] corner2 = CoordinateUtils.convertWGSToECEF(maxY, maxX, avgHeight);

        // Bounding sphere
        BoundingSphere.Sphere sphere = BoundingSphere.computeBoundingSphere(List.of(
                new BoundingSphere.Point3D(corner1[0], corner1[1], corner1[2]),
                new BoundingSphere.Point3D(corner2[0], corner2[1], corner2[2]),
                new BoundingSphere.Point3D(center[0], center[1], center[2])));

        // Horizon culling point
        Cartesian3 horizon;
        if (tileX == 0 && (tileY == 1 || tileY == 0)) {
            double horizonY = tileY == 1 ? 9.925954381079006e+23 : -9.925954381079006e+23;
            horizon = new Cartesian3(60778941.306355275, horizonY, 0);
        } else {
            Cartesian3[] positions = {
                    new Cartesian3(corner1[0], corner1[1], corner1[2]),
                    new Cartesian3(corner2[0], corner2[1], corner2[2])
            };
            horizon = Cartesian3.computeHorizonCullingPoint(
                    new Cartesian3(sphere.center.x, sphere.center.y, sphere.center.z), positions);
        }

        // Flatten elevation grid and generate mesh
        double[] terrain = new double[gridSize * gridSize];
        for (int y = 0; y < gridSize; y++) {
            for (int x = 0; x < gridSize; x++) {
                terrain[y * gridSize + x] = elevationData[x][y];
            }
        }
        MeshResult mesh = meshStrategy.generateMesh(gridSize, terrain, maxError, maxTriangleSpan);

        // Write output
        String filePath = outputFolder + zoom + File.separator + tileX + File.separator + tileY + ".terrain";
        createFolder(filePath);

        TerrainTileWriter.write(filePath,
                center[0], center[1], center[2],
                sphere.center.x, sphere.center.y, sphere.center.z,
                sphere.radius, minHeight, maxHeight,
                horizon.x, horizon.y, horizon.z,
                mesh, elevationData, gridSize);
    }

    private static void createFolder(String filePath) {
        File parentDir = new File(filePath).getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
    }
}
