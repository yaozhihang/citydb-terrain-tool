package org.citydb.raster;

import java.util.*;

/**
 * Simple regular-grid triangulation strategy.
 * Every grid cell is split into two triangles without any adaptive simplification.
 * This produces a dense, uniform mesh where all grid points are vertices.
 * The {@code maxError} and {@code maxTriangleSpan} parameters are ignored.
 */
public class SimpleGridMesh implements MeshStrategy {

    @Override
    public MeshResult generateMesh(int gridSize, double[] terrain, float maxError, int maxTriangleSpan) {
        int vertexCount = gridSize * gridSize;
        int numTriangles = (gridSize - 1) * (gridSize - 1) * 2;

        // Generate triangles with CCW winding in grid coordinates (y up)
        // Vertex index in row-major order: y * gridSize + x
        int[][] rawTriangles = new int[numTriangles][3];
        int ti = 0;
        for (int y = 0; y < gridSize - 1; y++) {
            for (int x = 0; x < gridSize - 1; x++) {
                int topLeft = y * gridSize + x;
                int topRight = topLeft + 1;
                int bottomLeft = (y + 1) * gridSize + x;
                int bottomRight = bottomLeft + 1;

                // CCW: topLeft → topRight → bottomLeft
                rawTriangles[ti][0] = topLeft;
                rawTriangles[ti][1] = topRight;
                rawTriangles[ti][2] = bottomLeft;
                ti++;

                // CCW: topRight → bottomRight → bottomLeft
                rawTriangles[ti][0] = topRight;
                rawTriangles[ti][1] = bottomRight;
                rawTriangles[ti][2] = bottomLeft;
                ti++;
            }
        }

        // Vertices are already in scanline order (row-major = ascending y, then x).
        // Sort triangles by minimum vertex index for high-water-mark compatibility.
        Arrays.sort(rawTriangles, (a, b) -> {
            int minA = Math.min(a[0], Math.min(a[1], a[2]));
            int minB = Math.min(b[0], Math.min(b[1], b[2]));
            return Integer.compare(minA, minB);
        });

        // Assign final vertex indices by first-appearance in sorted triangle list.
        // High-water-mark encoding requires vertex N to first appear when the
        // running counter equals N.
        int[] scanlineToFinal = new int[vertexCount];
        Arrays.fill(scanlineToFinal, -1);
        int nextIdx = 0;
        for (int[] tri : rawTriangles) {
            for (int v : tri) {
                if (scanlineToFinal[v] == -1) {
                    scanlineToFinal[v] = nextIdx++;
                }
            }
        }

        // Build triangle index array with final indices
        int[] triArray = new int[numTriangles * 3];
        for (int i = 0; i < numTriangles; i++) {
            triArray[i * 3]     = scanlineToFinal[rawTriangles[i][0]];
            triArray[i * 3 + 1] = scanlineToFinal[rawTriangles[i][1]];
            triArray[i * 3 + 2] = scanlineToFinal[rawTriangles[i][2]];
        }

        // Build vertex grid-index array: finalToScanline maps final index → row-major index
        int[] finalToScanline = new int[vertexCount];
        for (int s = 0; s < vertexCount; s++) {
            finalToScanline[scanlineToFinal[s]] = s;
        }
        int[] gridIndices = new int[vertexCount * 2];
        for (int f = 0; f < vertexCount; f++) {
            int s = finalToScanline[f];
            gridIndices[f * 2]     = s % gridSize; // x
            gridIndices[f * 2 + 1] = s / gridSize; // y
        }

        // Collect edge vertices in geographic order
        List<Integer> westEdge = new ArrayList<>();
        List<Integer> eastEdge = new ArrayList<>();
        List<Integer> southEdge = new ArrayList<>();
        List<Integer> northEdge = new ArrayList<>();

        for (int f = 0; f < vertexCount; f++) {
            int x = gridIndices[f * 2];
            int y = gridIndices[f * 2 + 1];
            if (x == 0) westEdge.add(f);
            if (x == gridSize - 1) eastEdge.add(f);
            if (y == 0) southEdge.add(f);
            if (y == gridSize - 1) northEdge.add(f);
        }
        westEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2 + 1]));
        eastEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2 + 1]));
        southEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2]));
        northEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2]));

        return new MeshResult(
                vertexCount,
                gridIndices,
                numTriangles,
                triArray,
                westEdge.stream().mapToInt(Integer::intValue).toArray(),
                southEdge.stream().mapToInt(Integer::intValue).toArray(),
                eastEdge.stream().mapToInt(Integer::intValue).toArray(),
                northEdge.stream().mapToInt(Integer::intValue).toArray()
        );
    }
}
