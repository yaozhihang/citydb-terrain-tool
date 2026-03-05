package org.citydb.raster;

import java.io.File;
import java.util.List;
import java.util.Map;

public class GeoTiffToQuantizedMesh {

    public static void createTMSTile(ElevationProvider provider, MeshStrategy meshStrategy,
                                      String outputFolder, int zoom, int tileX, int tileY,
                                      int gridSize, float maxError, int maxTriangleSpan,
                                      Map<String, double[]> cacheMap, boolean doStart) throws Exception {
        double[] bounds = CoordinateUtils.calculateTileBounds(tileX, tileY, zoom);
        double minX = bounds[0], maxX = bounds[1], minY = bounds[2], maxY = bounds[3];

        // Fetch elevation grid (includes raster sampling, smoothing, edge caching)
        double[][] elevationData = provider.fetchElevationGrid(bounds, gridSize, zoom, tileX, tileY, cacheMap, doStart);

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

        // ECEF coordinates
        double[] centerECEF = CoordinateUtils.convertWGSToECEF((minY + maxY) / 2, (minX + maxX) / 2, avgHeight);
        double centerX = centerECEF[0];
        double centerY = centerECEF[1];
        double centerZ = centerECEF[2];

        double[] cornerECEF1 = CoordinateUtils.convertWGSToECEF(minY, minX, avgHeight);
        double cornerX1 = cornerECEF1[0];
        double cornerY1 = cornerECEF1[1];
        double cornerZ1 = cornerECEF1[2];

        double[] cornerECEF2 = CoordinateUtils.convertWGSToECEF(maxY, maxX, avgHeight);
        double cornerX2 = cornerECEF2[0];
        double cornerY2 = cornerECEF2[1];
        double cornerZ2 = cornerECEF2[2];

        // Bounding sphere
        List<BoundingSphere.Point3D> points = List.of(
                new BoundingSphere.Point3D(cornerX1, cornerY1, cornerZ1),
                new BoundingSphere.Point3D(cornerX2, cornerY2, cornerZ2),
                new BoundingSphere.Point3D(centerX, centerY, centerZ)
        );
        BoundingSphere.Sphere boundingSphere = BoundingSphere.computeBoundingSphere(points);
        double bCenterX = boundingSphere.center.x;
        double bCenterY = boundingSphere.center.y;
        double bCenterZ = boundingSphere.center.z;
        double radius = boundingSphere.radius;

        // Horizon culling point
        double horizonCullingPointX, horizonCullingPointY, horizonCullingPointZ;
        if (tileX == 0 && (tileY == 1 || tileY == 0)) {
            horizonCullingPointX = 60778941.306355275;
            if (tileY == 1)
                horizonCullingPointY = 9.925954381079006e+23;
            else horizonCullingPointY = -9.925954381079006e+23;
            horizonCullingPointZ = 0;
        } else {
            Cartesian3[] positions = {
                    new Cartesian3(cornerX1, cornerY1, cornerZ1),
                    new Cartesian3(cornerX2, cornerY2, cornerZ2)
            };
            Cartesian3 horizonCullingPoint = Cartesian3.computeHorizonCullingPoint(
                    new Cartesian3(bCenterX, bCenterY, bCenterZ), positions);
            horizonCullingPointX = horizonCullingPoint.x;
            horizonCullingPointY = horizonCullingPoint.y;
            horizonCullingPointZ = horizonCullingPoint.z;
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

        QuantizedMeshWriter.write(filePath,
                centerX, centerY, centerZ,
                bCenterX, bCenterY, bCenterZ,
                radius, minHeight, maxHeight,
                horizonCullingPointX, horizonCullingPointY, horizonCullingPointZ,
                mesh, elevationData, gridSize);
    }

    private static void createFolder(String filePath) {
        File parentDir = new File(filePath).getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
            if (!parentDir.isDirectory()) {
                System.err.println("Failed to create parent directories: " + parentDir);
            }
        }
    }
}
