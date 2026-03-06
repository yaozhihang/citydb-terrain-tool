package org.citydb.raster.mesh;

/**
 * Result of a mesh generation strategy.
 * Contains quantized vertex grid indices, triangle indices, and edge vertex lists
 * needed by the quantized-mesh encoder.
 */
public class MeshResult {
    public final int vertexCount;
    public final int[] gridIndices;    // pairs of (x, y) grid coords
    public final int triangleCount;
    public final int[] triangles;      // triples of vertex indices
    public final int[] westEdge;
    public final int[] southEdge;
    public final int[] eastEdge;
    public final int[] northEdge;

    public MeshResult(int vertexCount, int[] gridIndices, int triangleCount, int[] triangles,
                      int[] westEdge, int[] southEdge, int[] eastEdge, int[] northEdge) {
        this.vertexCount = vertexCount;
        this.gridIndices = gridIndices;
        this.triangleCount = triangleCount;
        this.triangles = triangles;
        this.westEdge = westEdge;
        this.southEdge = southEdge;
        this.eastEdge = eastEdge;
        this.northEdge = northEdge;
    }
}
