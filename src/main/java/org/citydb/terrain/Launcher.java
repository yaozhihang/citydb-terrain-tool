package org.citydb.terrain;

import org.apache.commons.cli.*;
import org.citydb.terrain.operation.QMSGenerator;
import org.citydb.terrain.mesh.*;
import org.citydb.terrain.operation.TerrainImporter;
import org.citydb.terrain.provider.PostGISElevationProvider;

public class Launcher {

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
            printUsage();
            return;
        }

        String command = args[0];
        String[] remainingArgs = new String[args.length - 1];
        System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);

        switch (command.toLowerCase()) {
            case "import" -> runImport(remainingArgs);
            case "generate" -> runGenerate(remainingArgs);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: terrain-tool <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  import    Import XYZ terrain data (ZIP files) into database");
        System.out.println("  generate  Generate quantized mesh terrain tiles from database");
        System.out.println();
        System.out.println("Use 'terrain-tool <command> --help' for command-specific options.");
    }

    private static void runImport(String[] args) {
        Options options = createDbOptions();

        options.addOption(Option.builder("i")
                .longOpt("input")
                .hasArg()
                .required()
                .desc("Input folder containing XYZ ZIP files")
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
                formatter.printHelp("terrain-tool import [options]", options);
                return;
            }

            String dbUrl = buildDbUrl(cmd);
            String dbUser = cmd.getOptionValue("user");
            String dbPassword = cmd.getOptionValue("password");
            String schema = cmd.getOptionValue("schema", "public");
            String tableName = cmd.getOptionValue("table");
            String qualifiedTable = schema + "." + tableName;
            String inputFolder = cmd.getOptionValue("input");

            TerrainImporter.execute(inputFolder, dbUrl, dbUser, dbPassword, qualifiedTable);

        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            formatter.printHelp("terrain-tool import [options]", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error during import: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runGenerate(String[] args) {
        Options options = createDbOptions();

        options.addOption(Option.builder()
                .longOpt("minX")
                .hasArg()
                .type(Number.class)
                .desc("West extent longitude (default: from raster extent)")
                .build());

        options.addOption(Option.builder()
                .longOpt("maxX")
                .hasArg()
                .type(Number.class)
                .desc("East extent longitude (default: from raster extent)")
                .build());

        options.addOption(Option.builder()
                .longOpt("minY")
                .hasArg()
                .type(Number.class)
                .desc("South extent latitude (default: from raster extent)")
                .build());

        options.addOption(Option.builder()
                .longOpt("maxY")
                .hasArg()
                .type(Number.class)
                .desc("North extent latitude (default: from raster extent)")
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

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Print this help message")
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help")) {
                formatter.printHelp("terrain-tool generate [options]", options);
                return;
            }

            String dbUrl = buildDbUrl(cmd);
            String dbUser = cmd.getOptionValue("user");
            String dbPassword = cmd.getOptionValue("password");
            String schema = cmd.getOptionValue("schema", "public");
            String tableName = cmd.getOptionValue("table");
            String qualifiedTable = schema + "." + tableName;

            var elevationProvider = new PostGISElevationProvider(dbUrl, dbUser, dbPassword, qualifiedTable);

            // Query extent from raster table as default
            double[] extent = elevationProvider.queryExtent();
            double minX = parseDouble(cmd, "minX", extent[0]);
            double maxX = parseDouble(cmd, "maxX", extent[1]);
            double minY = parseDouble(cmd, "minY", extent[2]);
            double maxY = parseDouble(cmd, "maxY", extent[3]);

            int gridSize = parseInt(cmd, "gridSize", 33);
            int zoomLevel = parseInt(cmd, "zoom", 10);
            float baseError = (float) parseDouble(cmd, "error", 5.0);
            String outputFolder = cmd.getOptionValue("output", "viewer/terrain/");
            MeshStrategy meshStrategy = createMeshStrategy(cmd.getOptionValue("mesh", "delaunay"));

            QMSGenerator generator = new QMSGenerator(
                    minX, maxX, minY, maxY,
                    gridSize, zoomLevel, baseError,
                    outputFolder, meshStrategy, elevationProvider);

            generator.generate();

        } catch (ParseException e) {
            System.err.println("Error parsing arguments: " + e.getMessage());
            formatter.printHelp("terrain-tool generate [options]", options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error during generation: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Options createDbOptions() {
        Options options = new Options();

        options.addOption(Option.builder("H")
                .longOpt("host")
                .hasArg()
                .required()
                .desc("Database host")
                .build());

        options.addOption(Option.builder("P")
                .longOpt("port")
                .hasArg()
                .type(Number.class)
                .desc("Database port (default: 5432)")
                .build());

        options.addOption(Option.builder("d")
                .longOpt("db")
                .hasArg()
                .required()
                .desc("Database name")
                .build());

        options.addOption(Option.builder("u")
                .longOpt("user")
                .hasArg()
                .required()
                .desc("Database username")
                .build());

        options.addOption(Option.builder()
                .longOpt("password")
                .hasArg()
                .required()
                .desc("Database password")
                .build());

        options.addOption(Option.builder("s")
                .longOpt("schema")
                .hasArg()
                .desc("Database schema (default: public)")
                .build());

        options.addOption(Option.builder("t")
                .longOpt("table")
                .hasArg()
                .required()
                .desc("Database table name")
                .build());

        return options;
    }

    private static String buildDbUrl(CommandLine cmd) throws ParseException {
        String host = cmd.getOptionValue("host");
        int port = parseInt(cmd, "port", 5432);
        String dbName = cmd.getOptionValue("db");
        return "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
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
