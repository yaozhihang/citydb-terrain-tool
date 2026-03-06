package org.citydb.raster.provider;

import java.util.Map;

/**
 * Provides elevation data for terrain tile generation.
 * Implementations fetch elevation grids from different sources
 * (e.g., PostGIS database, local GeoTIFF files).
 */
public interface ElevationProvider extends AutoCloseable {

    /**
     * Fetch and prepare a complete elevation grid for a tile.
     *
     * @param bounds    tile bounds [minLon, maxLon, minLat, maxLat]
     * @param gridSize  side length of the square grid
     * @param zoom      zoom level (used for edge cache keys)
     * @param tileX     tile X coordinate (used for edge cache keys)
     * @param tileY     tile Y coordinate (used for edge cache keys)
     * @param cacheMap  shared edge cache for seamless tile boundaries
     * @param queryData whether to actually fetch elevation data (false = all zeros)
     * @return filled elevation grid [x][y]
     */
    double[][] fetchElevationGrid(double[] bounds, int gridSize, int zoom, int tileX, int tileY,
                                  Map<String, double[]> cacheMap, boolean queryData) throws Exception;

    @Override
    void close();
}
