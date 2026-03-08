package org.citydb.terrain.operation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.citydb.terrain.tile.TerrainTileCreator;
import org.citydb.terrain.mesh.MeshStrategy;
import org.citydb.terrain.provider.ElevationProvider;
import org.citydb.terrain.util.CoordinateUtils;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class QMSGenerator {

    private final double minX, maxX, minY, maxY;
    private final int gridSize;
    private final int zoomLevel;
    private final float baseError;
    private final String outputFolder;
    private final MeshStrategy meshStrategy;
    private final ElevationProvider elevationProvider;

    public QMSGenerator(double minX, double maxX, double minY, double maxY,
                        int gridSize, int zoomLevel, float baseError,
                        String outputFolder, MeshStrategy meshStrategy,
                        ElevationProvider elevationProvider) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.gridSize = gridSize;
        this.zoomLevel = zoomLevel;
        this.baseError = baseError;
        this.outputFolder = outputFolder;
        this.meshStrategy = meshStrategy;
        this.elevationProvider = elevationProvider;
    }

    public void generate() {
        Instant start = Instant.now();

        double[] dataExtent = {minX, maxX, minY, maxY};

        // Skip DB until the extent spans at least this many tiles (detail becomes visible)
        int minTilesVisible = 8;
        double extentSpan = Math.min(maxX - minX, maxY - minY);
        int skipDbZoom = (int) (Math.log(minTilesVisible * 180.0 / extentSpan) / Math.log(2));

        Map<String, double[]> cacheMap = new ConcurrentHashMap<>();

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(availableProcessors);

        int numTiles = 0;

        // Pre-calculate total number of tiles
        for (int t = 0; t <= zoomLevel; t++) {
            int[] tileMinBound = CoordinateUtils.lonLatToTile(minX, minY, t);
            int[] tileMaxBound = CoordinateUtils.lonLatToTile(maxX, maxY, t);
            numTiles += (tileMaxBound[0] - tileMinBound[0] + 1) * (tileMaxBound[1] - tileMinBound[1] + 1);
        }

        AtomicInteger counter = new AtomicInteger(numTiles);

        // Submit tile creation tasks
        for (int t = 0; t <= zoomLevel; t++) {
            int[] tileMinBound = CoordinateUtils.lonLatToTile(minX, minY, t);
            int[] tileMaxBound = CoordinateUtils.lonLatToTile(maxX, maxY, t);

            if (t == 0) {
                tileMinBound = new int[]{0, 0}; // Force zoom level 0 to start from 0,0
            }

            int currentGridSize = (t == zoomLevel) ? 129 : gridSize; // 2^7+1 for max zoom
            float maxError = baseError * (1 << (zoomLevel - t)); // baseError * 2^(maxZoom - currentZoom)
            if (t == zoomLevel) maxError = baseError;

            // At low zoom, tiles cover huge areas — force dense triangulation
            // so flat mesh follows earth curvature. Only affects few tiles.
            int maxTriangleSpan;
            if (t <= 2) maxTriangleSpan = 1;       // zoom 0-2: every grid cell splits
            else if (t <= 4) maxTriangleSpan = 2;   // zoom 3-4: max 2-cell span
            else maxTriangleSpan = (currentGridSize - 1) / 8; // zoom 5+: original behavior

            for (int i = tileMinBound[0]; i <= tileMaxBound[0]; i++) {
                for (int j = tileMinBound[1]; j <= tileMaxBound[1]; j++) {
                    int finalT = t, finalI = i, finalJ = j;

                    int finalGridSize = currentGridSize;
                    float finalMaxError = maxError;
                    int finalMaxTriangleSpan = maxTriangleSpan;
                    executor.submit(() -> {
                        try {
                            TerrainTileCreator.createTMSTile(elevationProvider, meshStrategy, outputFolder, finalT, finalI, finalJ, finalGridSize, finalMaxError, finalMaxTriangleSpan, cacheMap, dataExtent, skipDbZoom);
                            System.out.println("Remaining tiles: " + counter.getAndDecrement());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        }

        // Gracefully shutdown the executor without timeout
        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread interrupted while waiting for tasks to complete.");
            }
        }

        // Close elevation provider
        elevationProvider.close();

        // Finalize layer JSON
        createLayerJson();

        System.out.println("All tiles created successfully!");
        System.out.println("Total execution time: " + formatElapsedTime(Duration.between(start, Instant.now())));
    }

    private void createLayerJson() {
        JSONObject layerJson = new JSONObject();

        layerJson.put("tilejson", "2.1.0");
        layerJson.put("version", "1.1536156513599457");
        layerJson.put("format", "quantized-mesh-1.0");
        layerJson.put("scheme", "tms");

        layerJson.put("tiles", new String[]{
                "{z}/{x}/{y}.terrain?v={version}"
        });

        layerJson.put("minzoom", 0);
        layerJson.put("maxzoom", zoomLevel);

        layerJson.put("bounds", new double[]{-180.0, -90.0, 180.0, 90.0});

        layerJson.put("projection", "EPSG:4326");

        JSONArray availableTilesArray = new JSONArray();
        for (int t = 0; t <= zoomLevel; t++) {
            int[] tileMinBound = CoordinateUtils.lonLatToTile(minX, minY, t);
            int[] tileMaxBound = CoordinateUtils.lonLatToTile(maxX, maxY, t);

            if (t == 0) {
                tileMinBound = new int[]{0, 0};
            }

            JSONObject levelInfo = new JSONObject();
            levelInfo.put("startX", tileMinBound[0]);
            levelInfo.put("startY", tileMinBound[1]);
            levelInfo.put("endX", tileMaxBound[0]);
            levelInfo.put("endY", tileMaxBound[1]);
            JSONArray levelInfoArray = new JSONArray();
            levelInfoArray.add(levelInfo);
            availableTilesArray.add(levelInfoArray);
        }
        layerJson.put("available", availableTilesArray);

        String prettyJson = JSON.toJSONString(layerJson, JSONWriter.Feature.PrettyFormat);

        File folder = new File(outputFolder);
        File file = new File(folder, "layer.json");
        try (FileWriter fileWriter = new FileWriter(file)) {
            fileWriter.write(prettyJson);
            System.out.println("layer.json file created successfully!");
        } catch (IOException e) {
            System.err.println("An error occurred while writing the file: " + e.getMessage());
        }
    }

    private static String formatElapsedTime(Duration elapsed) {
        long d = elapsed.toDaysPart();
        long h = elapsed.toHoursPart();
        long m = elapsed.toMinutesPart();
        long s = elapsed.toSecondsPart();

        if (d > 0) {
            return String.format("%02d d, %02d h, %02d m, %02d s", d, h, m, s);
        } else if (h > 0) {
            return String.format("%02d h, %02d m, %02d s", h, m, s);
        } else if (m > 0) {
            return String.format("%02d m, %02d s", m, s);
        } else {
            return String.format("%02d s", s);
        }
    }
}
