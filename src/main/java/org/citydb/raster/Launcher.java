package org.citydb.raster;

import org.apache.commons.cli.*;
import org.citydb.raster.io.TerrainGenerator;
import org.citydb.raster.mesh.*;
import org.citydb.raster.provider.ElevationProvider;
import org.citydb.raster.provider.PointCloudElevationProvider;
import org.citydb.raster.provider.PostGISElevationProvider;

public class Launcher {

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder()
                .longOpt("minX")
                .hasArg()
                .type(Number.class)
                .desc("West extent longitude (default: 8.97205)")
                .build());

        options.addOption(Option.builder()
                .longOpt("maxX")
                .hasArg()
                .type(Number.class)
                .desc("East extent longitude (default: 13.84636)")
                .build());

        options.addOption(Option.builder()
                .longOpt("minY")
                .hasArg()
                .type(Number.class)
                .desc("South extent latitude (default: 47.26887)")
                .build());

        options.addOption(Option.builder()
                .longOpt("maxY")
                .hasArg()
                .type(Number.class)
                .desc("North extent latitude (default: 50.56651)")
                .build());

        options.addOption(Option.builder()
                .longOpt("gridSize")
                .hasArg()
                .type(Number.class)
                .desc("Grid size, must be 2^n+1 for RTIN (default: 33)")
                .build());

        options.addOption(Option.builder("z")
                .longOpt("zoom")
                .hasArg()
                .type(Number.class)
                .desc("Max zoom level (default: 10)")
                .build());

        options.addOption(Option.builder("e")
                .longOpt("error")
                .hasArg()
                .type(Number.class)
                .desc("Base error in meters (default: 5.0)")
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .desc("Output folder (default: viewer/terrain/)")
                .build());

        options.addOption(Option.builder("m")
                .longOpt("mesh")
                .hasArg()
                .desc("Mesh strategy: delaunay, rtin, simple (default: delaunay)")
                .build());

        options.addOption(Option.builder("p")
                .longOpt("provider")
                .hasArg()
                .desc("Elevation provider: raster, pointcloud (default: raster)")
                .build());

        options.addOption(Option.builder("d")
                .longOpt("db")
                .hasArg()
                .desc("JDBC database URL (default: jdbc:postgresql://localhost:5432/bayern_dem_raster)")
                .build());

        options.addOption(Option.builder("u")
                .longOpt("user")
                .hasArg()
                .desc("Database username (default: postgres)")
                .build());

        options.addOption(Option.builder()
                .longOpt("password")
                .hasArg()
                .desc("Database password (default: 125125)")
                .build());

        options.addOption(Option.builder("t")
                .longOpt("table")
                .hasArg()
                .desc("Database table name (default: raster_table or point_cloud)")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Print this help message")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help")) {
                formatter.printHelp("terrain-tile", options);
                return;
            }

            double minX = parseDouble(cmd, "minX", 8.97205);
            double maxX = parseDouble(cmd, "maxX", 13.84636);
            double minY = parseDouble(cmd, "minY", 47.26887);
            double maxY = parseDouble(cmd, "maxY", 50.56651);
            int gridSize = parseInt(cmd, "gridSize", 33);
            int zoomLevel = parseInt(cmd, "zoom", 10);
            float baseError = (float) parseDouble(cmd, "error", 5.0);
            String outputFolder = cmd.getOptionValue("output", "viewer/terrain/");
            MeshStrategy meshStrategy = createMeshStrategy(cmd.getOptionValue("mesh", "delaunay"));

            String dbUrl = cmd.getOptionValue("db", "jdbc:postgresql://localhost:5432/bayern_dem_raster");
            String dbUser = cmd.getOptionValue("user", "postgres");
            String dbPassword = cmd.getOptionValue("password", "125125");
            String providerType = cmd.getOptionValue("provider", "raster");
            String tableName = cmd.getOptionValue("table",
                    providerType.equalsIgnoreCase("pointcloud") ? "point_cloud" : "raster_table");

            ElevationProvider elevationProvider = createElevationProvider(providerType, dbUrl, dbUser, dbPassword, tableName);

            TerrainGenerator generator = new TerrainGenerator(
                    minX, maxX, minY, maxY,
                    gridSize, zoomLevel, baseError,
                    outputFolder, meshStrategy, elevationProvider);

            generator.generate();

        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            formatter.printHelp("terrain-tile", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error during tile generation: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static ElevationProvider createElevationProvider(String type, String url, String user, String password, String table) {
        return switch (type.toLowerCase()) {
            case "raster" -> new PostGISElevationProvider(url, user, password, table);
            case "pointcloud" -> new PointCloudElevationProvider(url, user, password, table);
            default -> {
                System.err.println("Unknown provider: " + type + ". Using raster.");
                yield new PostGISElevationProvider(url, user, password, table);
            }
        };
    }

    private static MeshStrategy createMeshStrategy(String type) {
        return switch (type.toLowerCase()) {
            case "rtin" -> new RtinMesh();
            case "delaunay" -> new DelaunayMesh();
            case "simple" -> new SimpleGridMesh();
            default -> {
                System.err.println("Unknown mesh strategy: " + type + ". Using delaunay.");
                yield new DelaunayMesh();
            }
        };
    }

    private static double parseDouble(CommandLine cmd, String option, double defaultValue) throws ParseException {
        return cmd.hasOption(option)
                ? ((Number) cmd.getParsedOptionValue(option)).doubleValue() : defaultValue;
    }

    private static int parseInt(CommandLine cmd, String option, int defaultValue) throws ParseException {
        return cmd.hasOption(option)
                ? ((Number) cmd.getParsedOptionValue(option)).intValue() : defaultValue;
    }
}
