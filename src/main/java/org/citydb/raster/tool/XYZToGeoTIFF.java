package org.citydb.raster.tool;

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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class XYZToGeoTIFF {

    static class XYZPoint {
        double x, y, z;

        public XYZPoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static void main(String[] args) {
        // Path to the folder containing ZIP files
        String folderPath = "C:\\Daten\\Bayern_DEM\\xyz";
        String outputFolder = "C:\\Daten\\Bayern_DEM\\tif\\5m";

        try {
            // Iterate through the folder
            File folder = new File(folderPath);
            if (folder.exists() && folder.isDirectory()) {
                File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
                int size = files.length;
                if (files != null) {
                    for (File zipFile : files) {
                        System.out.println(size-- + " Reading ZIP file: " + zipFile.getName());
                        processZipFile(zipFile.toPath(), outputFolder);
                    }
                } else {
                    System.out.println("No ZIP files found in the folder.");
                }
            } else {
                System.out.println("Folder does not exist or is not a directory.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void processZipFile(Path zipFilePath, String outputFolder) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry entry;
            List<XYZPoint> xyzPoints = new ArrayList<>();

            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zis));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] values = line.split("\\s+");
                        if (values.length >= 3) {
                            double x = Double.parseDouble(values[0]);
                            double y = Double.parseDouble(values[1]);
                            double z = Double.parseDouble(values[2]);
                            if (z > 0) {
                                xyzPoints.add(new XYZPoint(x, y, z));
                            }
                        }
                    }

                    File folder = new File(outputFolder);
                    File file = new File(folder, entry.getName().substring(0, entry.getName().lastIndexOf('.')) + ".tif");

                    createGeoTIFF(xyzPoints, file.getAbsolutePath());
                    System.out.println("GeoTIFF (" + xyzPoints.size() + " points) successfully created at: " + file.getAbsolutePath());
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            System.err.println("Error reading ZIP file: " + zipFilePath.getFileName());
            e.printStackTrace();
        }
    }

    private static void createGeotiffFile(String inputFilePath, String outputFilePath) {
        try {
            // Step 1: Read the XYZ file
            List<XYZPoint> xyzPoints = readXYZFile(inputFilePath);
            System.out.println("XYZ file successfully read: " + xyzPoints.size() + " points");
            // Step 2: Create a GeoTIFF from the XYZ points
            createGeoTIFF(xyzPoints, outputFilePath);
            System.out.println("GeoTIFF (" + xyzPoints.size() + " points) successfully created at: " + outputFilePath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<XYZPoint> readXYZFile(String filePath) throws Exception {
        List<XYZPoint> points = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split("\\s+");
                if (values.length >= 3) {
                    double x = Double.parseDouble(values[0]);
                    double y = Double.parseDouble(values[1]);
                    double z = Double.parseDouble(values[2]);
                    points.add(new XYZPoint(x, y, z));
                }
            }
        }
        return points;
    }

    private static void createGeoTIFF(List<XYZPoint> points, String outputFilePath) throws Exception {
        // Step 1: Calculate bounds and grid size
        double minX = points.stream().mapToDouble(p -> p.x).min().orElseThrow();
        double maxX = points.stream().mapToDouble(p -> p.x).max().orElseThrow();
        double minY = points.stream().mapToDouble(p -> p.y).min().orElseThrow();
        double maxY = points.stream().mapToDouble(p -> p.y).max().orElseThrow();

        int width = 200;  // Number of grid cells in X
        int height = 200; // Number of grid cells in Y
        double cellSizeX = (maxX - minX) / (width - 1);
        double cellSizeY = (maxY - minY) / (height - 1);

        // Step 2: Create a WritableRaster and populate it with Z values
        BandedSampleModel sampleModel = new BandedSampleModel(DataBuffer.TYPE_DOUBLE, width, height, 1);

        WritableRaster raster = Raster.createWritableRaster(sampleModel, null);
        for (XYZPoint point : points) {
            int xIndex = (int) Math.floor((point.x - minX) / cellSizeX);
            int yIndex = (int) Math.floor((point.y - minY) / cellSizeY);

            // Ensure indices are within bounds
            if (xIndex >= 0 && xIndex < width && yIndex >= 0 && yIndex < height) {
                raster.setSample(xIndex, height - yIndex - 1, 0, point.z); // Flip Y-axis
            }
        }

        // Step 3: Create a GeoTIFF with a CRS (Coordinate Reference System)
        CoordinateReferenceSystem crs = CRS.decode("EPSG:25832"); // WGS84
        ReferencedEnvelope envelope = new ReferencedEnvelope(minX - 2.5, maxX + 2.5, minY - 2.5, maxY + 2.5, crs);
        GridCoverageFactory factory = new GridCoverageFactory();
        GridCoverage2D coverage = factory.create("Terrain", raster, envelope);

        // Step 4: Write the GeoTIFF file
        File outputFile = new File(outputFilePath);
        GeoTiffWriter writer = null;
        try {
            writer = new GeoTiffWriter(outputFile);
            writer.write(coverage, null);
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new RuntimeException(e);
        } finally {
            if (writer != null) {
                writer.dispose();
            }
        }
    }
}
