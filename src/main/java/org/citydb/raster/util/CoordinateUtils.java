package org.citydb.raster.util;

/**
 * Pure geodesy and coordinate utilities for TMS tile generation.
 * All methods are stateless and static.
 */
public class CoordinateUtils {

    // WGS84 ellipsoid constants
    static final double SEMI_MAJOR_AXIS = 6378137.0;
    static final double FLATTENING = 1 / 298.257223563;
    static final double ECCENTRICITY_SQUARED = FLATTENING * (2 - FLATTENING);

    public static double[] convertWGSToECEF(double latitude, double longitude, double altitude) {
        double latRad = Math.toRadians(latitude);
        double lonRad = Math.toRadians(longitude);

        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double N = SEMI_MAJOR_AXIS / Math.sqrt(1 - ECCENTRICITY_SQUARED * sinLat * sinLat);

        double x = (N + altitude) * cosLat * Math.cos(lonRad);
        double y = (N + altitude) * cosLat * Math.sin(lonRad);
        double z = ((1 - ECCENTRICITY_SQUARED) * N + altitude) * sinLat;

        return new double[]{x, y, z};
    }

    public static int[] lonLatToTile(double lon, double lat, int zoom) {
        int tilesAtZoom = 1 << zoom;
        int tx = (int) (Math.floor(tilesAtZoom * 2 * (lon + 180) / 360));
        int ty = (int) (Math.floor(tilesAtZoom * (lat + 90) / 180));
        return new int[]{tx, ty};
    }

    public static double[] calculateTileBounds(int tileX, int tileY, int zoom) {
        int tilesAtZoom = 1 << zoom;
        double minLon = tileX / (double) tilesAtZoom * 180.0 - 180.0;
        double maxLon = minLon + 180.0 / tilesAtZoom;
        double minLat = tileY / (double) tilesAtZoom * 180.0 - 90.0;
        double maxLat = minLat + 180.0 / tilesAtZoom;
        return new double[]{minLon, maxLon, minLat, maxLat};
    }
}
