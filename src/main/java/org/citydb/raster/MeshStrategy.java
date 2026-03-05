package org.citydb.raster;

/**
 * Strategy interface for mesh generation from elevation grids.
 * Implementations produce a {@link MeshResult} containing vertices, triangles,
 * and edge indices suitable for quantized-mesh encoding.
 */
public interface MeshStrategy {
    /**
     * Generate a triangle mesh from an elevation grid.
     *
     * @param gridSize         side length of the square grid (e.g., 33 or 129)
     * @param terrain          1D row-major elevation values: terrain[y * gridSize + x]
     * @param maxError         maximum allowable interpolation error (meters)
     * @param maxTriangleSpan  max grid cells a triangle edge may span (controls density)
     * @return mesh result with vertices, triangles, and edge indices
     */
    MeshResult generateMesh(int gridSize, double[] terrain, float maxError, int maxTriangleSpan);
}
