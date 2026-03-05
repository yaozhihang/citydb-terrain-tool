package org.citydb.raster;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class RasterImporter {

    public static void main(String[] args) {
        String dbUrl = "jdbc:postgresql://localhost:5432/bayern_dem_raster";
        String dbUser = "postgres";
        String dbPassword = "125125";

        // Path to the folder containing ZIP files
        String folderPath = "C:\\Daten\\Bayern_DEM\\tif\\5m";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            System.out.println("Connected to the database!");
            File folder = new File(folderPath);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".tif"));

                if (files != null) {
                    int size = files.length;
                    for (int i = 0; i < files.length; i++) {
                        File tiffFile = files[i];
                        System.out.println(size-- + " Reading GeoTIFF file: " + tiffFile.getName());
                        importRasterFile(i, tiffFile, conn);
                    }
                } else {
                    System.out.println("No GeoTIFF files found in the folder.");
                }
            } else {
                System.out.println("Folder does not exist or is not a directory.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void importRasterFile(int index, File rasterFile, Connection conn) throws Exception {
        try (InputStream inputStream = new FileInputStream(rasterFile)) {
            // Insert the raster into the PostGIS table
            String sql = "INSERT INTO raster_table (rid, rast) VALUES (?, ST_FromGDALRaster(?))";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, index);
                stmt.setBinaryStream(2, inputStream, (int) rasterFile.length());
                stmt.executeUpdate();
                System.out.println("Raster file imported successfully!");
            }
        }
    }
}
