package org.citydb.terrain.operation;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;

import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads ZIP/XYZ files containing terrain XYZ data, converts to raster,
 * and imports directly into a PostGIS database using multi-threading.
 */
public class TerrainImporter {

    private static final String CRS_CODE = "EPSG:25832";

    private record XYZPoint(double x, double y, double z) {}

    public static void execute(String folderPath, String dbUrl, String dbUser,
                                String dbPassword, String tableName) throws Exception {
        Instant start = Instant.now();

        DataSource dataSource = createDataSource(dbUrl, dbUser, dbPassword);

        try (Connection conn = dataSource.getConnection()) {
            System.out.println("Connected to the database!");
            ensureTableExists(conn, tableName);
        }

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            dataSource.close();
            throw new IllegalArgumentException("Folder does not exist or is not a directory: " + folderPath);
        }

        File[] files = folder.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".zip") || lower.endsWith(".xyz");
        });
        if (files == null || files.length == 0) {
            dataSource.close();
            System.out.println("No ZIP or XYZ files found in the folder.");
            return;
        }

        int total = files.length;
        System.out.println("Found " + total + " file(s) to import.");

        AtomicInteger ridCounter = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        System.out.println("Using " + threads + " threads.");

        for (File file : files) {
            executor.submit(() -> {
                try {
                    if (file.getName().toLowerCase().endsWith(".zip")) {
                        processZipFile(file.toPath(), dataSource, tableName, ridCounter);
                    } else {
                        processXyzFile(file.toPath(), dataSource, tableName, ridCounter);
                    }
                    int done = completed.incrementAndGet();
                    System.out.printf("[%d/%d] Completed: %s%n", done, total, file.getName());
                } catch (Exception e) {
                    System.err.println("Error processing: " + file.getName());
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Thread interrupted while waiting for tasks to complete.");
            }
        }

        dataSource.close();

        Duration elapsed = Duration.between(start, Instant.now());
        System.out.println("All files imported. Total rasters: " + ridCounter.get());
        System.out.println("Total execution time: " + formatElapsedTime(elapsed));
    }

    private static DataSource createDataSource(String url, String user, String password) {
        DataSource ds = new DataSource();
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setInitialSize(2);
        ds.setMaxActive(Runtime.getRuntime().availableProcessors());
        return ds;
    }

    private static void ensureTableExists(Connection conn, String tableName) throws SQLException {
        String schema = null;
        String table = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            schema = parts[0];
            table = parts[1];
        }

        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, schema, table, new String[]{"TABLE"})) {
            if (rs.next()) {
                System.out.println("Table '" + tableName + "' already exists.");
                return;
            }
        }
        String sql = "CREATE TABLE " + tableName + " (rid SERIAL PRIMARY KEY, rast RASTER)";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            System.out.println("Table '" + tableName + "' created.");
        }
    }

    private static void processXyzFile(Path xyzFilePath, DataSource dataSource,
                                        String tableName, AtomicInteger ridCounter) throws Exception {
        try (InputStream is = Files.newInputStream(xyzFilePath)) {
            List<XYZPoint> points = readXYZPoints(is);
            if (points.isEmpty()) {
                System.out.println("  Skipping empty file: " + xyzFilePath.getFileName());
                return;
            }

            byte[] geotiffBytes = createGeoTIFFBytes(points);
            int rid = ridCounter.getAndIncrement();
            try (Connection conn = dataSource.getConnection()) {
                importRaster(rid, geotiffBytes, conn, tableName);
            }
        }
    }

    private static void processZipFile(Path zipFilePath, DataSource dataSource,
                                        String tableName, AtomicInteger ridCounter) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xyz")) {
                    zis.closeEntry();
                    continue;
                }

                List<XYZPoint> points = readXYZPoints(zis);
                if (points.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                byte[] geotiffBytes = createGeoTIFFBytes(points);
                int rid = ridCounter.getAndIncrement();
                try (Connection conn = dataSource.getConnection()) {
                    importRaster(rid, geotiffBytes, conn, tableName);
                }
                zis.closeEntry();
            }
        }
    }

    private static List<XYZPoint> readXYZPoints(InputStream is) throws IOException {
        List<XYZPoint> points = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = line.split("\\s+");
            if (values.length >= 3) {
                try {
                    double x = Double.parseDouble(values[0]);
                    double y = Double.parseDouble(values[1]);
                    double z = Double.parseDouble(values[2]);
                    if (z > 0) {
                        points.add(new XYZPoint(x, y, z));
                    }
                } catch (NumberFormatException ignored) {
                    // skip non-numeric lines (headers, metadata, etc.)
                }
            }
        }
        return points;
    }

    private static byte[] createGeoTIFFBytes(List<XYZPoint> points) throws Exception {
        TreeSet<Double> uniqueX = new TreeSet<>();
        TreeSet<Double> uniqueY = new TreeSet<>();
        for (XYZPoint p : points) {
            uniqueX.add(p.x);
            uniqueY.add(p.y);
        }

        int width = uniqueX.size();
        int height = uniqueY.size();
        double minX = uniqueX.first(), maxX = uniqueX.last();
        double minY = uniqueY.first(), maxY = uniqueY.last();
        double cellSizeX = (maxX - minX) / (width - 1);
        double cellSizeY = (maxY - minY) / (height - 1);

        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_DOUBLE, width, height, 1);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, null);
        for (XYZPoint point : points) {
            int xIndex = (int) Math.floor((point.x - minX) / cellSizeX);
            int yIndex = (int) Math.floor((point.y - minY) / cellSizeY);
            if (xIndex >= 0 && xIndex < width && yIndex >= 0 && yIndex < height) {
                raster.setSample(xIndex, height - yIndex - 1, 0, point.z);
            }
        }

        CoordinateReferenceSystem crs = CRS.decode(CRS_CODE);
        double halfCellX = cellSizeX / 2;
        double halfCellY = cellSizeY / 2;
        ReferencedEnvelope envelope = new ReferencedEnvelope(
                minX - halfCellX, maxX + halfCellX, minY - halfCellY, maxY + halfCellY, crs);
        GridCoverageFactory factory = new GridCoverageFactory();
        GridCoverage2D coverage = factory.create("Terrain", raster, envelope);

        Path tempFile = Files.createTempFile("xyz_raster_", ".tif");
        try {
            GeoTiffWriter writer = new GeoTiffWriter(tempFile.toFile());
            try {
                writer.write(coverage);
            } finally {
                writer.dispose();
            }
            return Files.readAllBytes(tempFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void importRaster(int rid, byte[] geotiffBytes, Connection conn, String tableName) throws Exception {
        String sql = "INSERT INTO " + tableName + " (rid, rast) VALUES (?, ST_FromGDALRaster(?))";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, rid);
            stmt.setBytes(2, geotiffBytes);
            stmt.executeUpdate();
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
