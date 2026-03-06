package org.citydb.terrain.tool;

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
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class XYZToGeoTIFF {

    private static final String CRS_CODE = "EPSG:25832";

    record XYZPoint(double x, double y, double z) {}

    public static void main(String[] args) {
        String folderPath = "C:\\Daten\\Bayern_DEM\\xyz";
        String outputFolder = "C:\\Daten\\Bayern_DEM\\tif\\5m";

        try {
            File folder = new File(folderPath);
            if (!folder.exists() || !folder.isDirectory()) {
                System.out.println("Folder does not exist or is not a directory.");
                return;
            }

            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (files == null || files.length == 0) {
                System.out.println("No ZIP files found in the folder.");
                return;
            }

            int remaining = files.length;
            for (File zipFile : files) {
                System.out.println(remaining-- + " Reading ZIP file: " + zipFile.getName());
                processZipFile(zipFile.toPath(), outputFolder);
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

                    File file = new File(outputFolder,
                            entry.getName().substring(0, entry.getName().lastIndexOf('.')) + ".tif");
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

    private static void createGeoTIFF(List<XYZPoint> points, String outputFilePath) throws Exception {
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

        GeoTiffWriter writer = new GeoTiffWriter(new File(outputFilePath));
        try {
            writer.write(coverage);
        } finally {
            writer.dispose();
        }
    }
}
