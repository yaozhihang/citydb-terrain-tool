package org.citydb.terrain.operation;

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
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads ZIP files containing terrain XYZ data, converts to raster,
 * and imports directly into a PostGIS database.
 */
public class TerrainImporter {

    private static final String CRS_CODE = "EPSG:25832";

    private record XYZPoint(double x, double y, double z) {}

    public static void execute(String folderPath, String dbUrl, String dbUser,
                                String dbPassword, String tableName) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            System.out.println("Connected to the database!");

            ensureTableExists(conn, tableName);

            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) {
                throw new IllegalArgumentException("Folder does not exist or is not a directory: " + folderPath);
            }

            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (files == null || files.length == 0) {
                System.out.println("No ZIP files found in the folder.");
                return;
            }

            int rid = 0;
            int remaining = files.length;
            for (File zipFile : files) {
                System.out.println(remaining-- + " Processing ZIP file: " + zipFile.getName());
                rid = processZipFile(zipFile.toPath(), conn, tableName, rid);
            }

            System.out.println("All files imported. Total rasters: " + rid);
        }
    }

    private static void ensureTableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
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

    private static int processZipFile(Path zipFilePath, Connection conn, String tableName, int rid) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                List<XYZPoint> points = readXYZPoints(zis);
                if (points.isEmpty()) {
                    System.out.println("  Skipping empty entry: " + entry.getName());
                    zis.closeEntry();
                    continue;
                }

                byte[] geotiffBytes = createGeoTIFFBytes(points);
                importRaster(rid, geotiffBytes, conn, tableName);
                System.out.println("  Imported " + entry.getName()
                        + " (" + points.size() + " points, rid=" + rid + ")");
                rid++;
                zis.closeEntry();
            }
        } catch (Exception e) {
            System.err.println("Error processing ZIP file: " + zipFilePath.getFileName());
            e.printStackTrace();
        }
        return rid;
    }

    private static List<XYZPoint> readXYZPoints(InputStream is) throws IOException {
        List<XYZPoint> points = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] values = line.split("\\s+");
            if (values.length >= 3) {
                double x = Double.parseDouble(values[0]);
                double y = Double.parseDouble(values[1]);
                double z = Double.parseDouble(values[2]);
                if (z > 0) {
                    points.add(new XYZPoint(x, y, z));
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

        // Write GeoTIFF to a temp file, then read bytes
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
}
