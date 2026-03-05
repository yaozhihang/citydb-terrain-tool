package org.citydb.raster;

import java.util.*;

/**
 * RTIN (Right-Triangulated Irregular Network) adaptive mesh generator.
 * Uses midpoint-based error indexing (similar to mapbox/martini).
 * Each triangle's error is stored at its hypotenuse midpoint: errors[my * gridSize + mx].
 */
public class RtinMesh implements MeshStrategy {

    private int gridSize;      // must be 2^n+1
    private double[] terrain;  // 1D row-major: terrain[y * gridSize + x]
    private double[] errors;   // indexed by midpoint: errors[my * gridSize + mx]

    /** Create an instance for use via the {@link MeshStrategy} interface. */
    public RtinMesh() {}

    /** Create an instance pre-loaded with terrain data for two-phase usage. */
    public RtinMesh(int gridSize, double[] terrain) {
        init(gridSize, terrain);
    }

    private void init(int gridSize, double[] terrain) {
        if (terrain.length != gridSize * gridSize) {
            throw new IllegalArgumentException("terrain length must be gridSize * gridSize");
        }
        // Validate gridSize is 2^n+1
        int n = gridSize - 1;
        if (n <= 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("gridSize must be 2^n+1, got " + gridSize);
        }
        this.gridSize = gridSize;
        this.terrain = terrain;
        this.errors = new double[gridSize * gridSize];
    }

    @Override
    public MeshResult generateMesh(int gridSize, double[] terrain, float maxError) {
        // Create a dedicated instance to ensure thread safety
        // (this object may be shared across threads)
        RtinMesh instance = new RtinMesh(gridSize, terrain);
        instance.computeErrors();
        return instance.extractMesh(maxError);
    }

    /**
     * Bottom-up error computation for all triangles.
     * Processes both root triangles. Errors are stored at each triangle's
     * hypotenuse midpoint and propagated upward (parent gets max of own error and children).
     */
    public void computeErrors() {
        Arrays.fill(errors, 0);

        // Root triangle 0: bottom-left half
        // Hypotenuse: (0, gridSize-1) to (gridSize-1, 0), apex: (0, 0)
        computeErrorsRecursive(
                0, gridSize - 1,
                gridSize - 1, 0,
                0, 0);

        // Root triangle 1: top-right half
        // Hypotenuse: (gridSize-1, 0) to (0, gridSize-1), apex: (gridSize-1, gridSize-1)
        computeErrorsRecursive(
                gridSize - 1, 0,
                0, gridSize - 1,
                gridSize - 1, gridSize - 1);
    }

    private void computeErrorsRecursive(int ax, int ay, int bx, int by, int cx, int cy) {
        int mx = (ax + bx) >> 1;
        int my = (ay + by) >> 1;

        // Base case: hypotenuse endpoints are adjacent, no further subdivision
        if (Math.abs(ax - bx) <= 1 && Math.abs(ay - by) <= 1) {
            return;
        }

        // Recurse into children first (bottom-up)
        // Left child: hypotenuse (cx,cy)-(ax,ay), apex (mx,my)
        computeErrorsRecursive(cx, cy, ax, ay, mx, my);
        // Right child: hypotenuse (bx,by)-(cx,cy), apex (mx,my)
        computeErrorsRecursive(bx, by, cx, cy, mx, my);

        // Interpolation error at the midpoint
        double interpolated = (terrain[ay * gridSize + ax] + terrain[by * gridSize + bx]) / 2.0;
        double actual = terrain[my * gridSize + mx];
        double error = Math.abs(actual - interpolated);

        // Propagate children's max errors upward
        // Left child midpoint
        int lmx = (cx + ax) >> 1;
        int lmy = (cy + ay) >> 1;
        if (Math.abs(cx - ax) > 1 || Math.abs(cy - ay) > 1) {
            error = Math.max(error, errors[lmy * gridSize + lmx]);
        }
        // Right child midpoint
        int rmx = (bx + cx) >> 1;
        int rmy = (by + cy) >> 1;
        if (Math.abs(bx - cx) > 1 || Math.abs(by - cy) > 1) {
            error = Math.max(error, errors[rmy * gridSize + rmx]);
        }

        int idx = my * gridSize + mx;
        // Use max to handle overlap at center point (shared by both root triangles)
        errors[idx] = Math.max(errors[idx], error);
    }

    /**
     * Extract an adaptive mesh by top-down traversal of the two root triangles.
     * Splits a triangle if its error exceeds maxError or if its midpoint is on a tile edge.
     */
    public MeshResult extractMesh(float maxError) {
        Map<Long, Integer> vertexMap = new LinkedHashMap<>();
        List<int[]> triangles = new ArrayList<>();

        // Always add four corners
        addVertex(vertexMap, 0, 0);
        addVertex(vertexMap, gridSize - 1, 0);
        addVertex(vertexMap, 0, gridSize - 1);
        addVertex(vertexMap, gridSize - 1, gridSize - 1);

        // Root triangle 0: bottom-left
        extractTriangle(
                0, gridSize - 1,
                gridSize - 1, 0,
                0, 0,
                maxError, vertexMap, triangles);

        // Root triangle 1: top-right
        extractTriangle(
                gridSize - 1, 0,
                0, gridSize - 1,
                gridSize - 1, gridSize - 1,
                maxError, vertexMap, triangles);

        // Build sorted vertex list (scanline order: y ascending, then x ascending)
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(vertexMap.entrySet());
        entries.sort((a, b) -> {
            int ay = (int) (a.getKey() >> 32);
            int axx = (int) (a.getKey() & 0xFFFFFFFFL);
            int by2 = (int) (b.getKey() >> 32);
            int bxx = (int) (b.getKey() & 0xFFFFFFFFL);
            if (ay != by2) return Integer.compare(ay, by2);
            return Integer.compare(axx, bxx);
        });

        // Re-index vertices in scanline order
        int[] reindex = new int[vertexMap.size()];
        int[] gridIndices = new int[entries.size() * 2];
        for (int i = 0; i < entries.size(); i++) {
            long key = entries.get(i).getKey();
            int oldIndex = entries.get(i).getValue();
            reindex[oldIndex] = i;
            gridIndices[i * 2] = (int) (key & 0xFFFFFFFFL);     // x
            gridIndices[i * 2 + 1] = (int) (key >> 32);          // y
        }

        // Re-index triangles and sort by minimum vertex index
        int[][] triSortable = new int[triangles.size()][3];
        for (int i = 0; i < triangles.size(); i++) {
            int[] tri = triangles.get(i);
            triSortable[i][0] = reindex[tri[0]];
            triSortable[i][1] = reindex[tri[1]];
            triSortable[i][2] = reindex[tri[2]];
        }

        // Sort triangles by minimum vertex index for high-water-mark encoding
        Arrays.sort(triSortable, (a, b) -> {
            int minA = Math.min(a[0], Math.min(a[1], a[2]));
            int minB = Math.min(b[0], Math.min(b[1], b[2]));
            return Integer.compare(minA, minB);
        });

        int[] triArray = new int[triSortable.length * 3];
        for (int i = 0; i < triSortable.length; i++) {
            triArray[i * 3] = triSortable[i][0];
            triArray[i * 3 + 1] = triSortable[i][1];
            triArray[i * 3 + 2] = triSortable[i][2];
        }

        // Collect edge vertices (already in scanline order from sorted entries)
        List<Integer> westEdge = new ArrayList<>();
        List<Integer> eastEdge = new ArrayList<>();
        List<Integer> southEdge = new ArrayList<>();
        List<Integer> northEdge = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            long key = entries.get(i).getKey();
            int x = (int) (key & 0xFFFFFFFFL);
            int y = (int) (key >> 32);

            if (x == 0) westEdge.add(i);
            if (x == gridSize - 1) eastEdge.add(i);
            if (y == 0) southEdge.add(i);
            if (y == gridSize - 1) northEdge.add(i);
        }

        return new MeshResult(
                entries.size(),
                gridIndices,
                triSortable.length,
                triArray,
                westEdge.stream().mapToInt(Integer::intValue).toArray(),
                southEdge.stream().mapToInt(Integer::intValue).toArray(),
                eastEdge.stream().mapToInt(Integer::intValue).toArray(),
                northEdge.stream().mapToInt(Integer::intValue).toArray()
        );
    }

    /** Check if at least two of the three values equal edgeVal. */
    private boolean twoOnEdge(int a, int b, int c, int edgeVal) {
        int count = 0;
        if (a == edgeVal) count++;
        if (b == edgeVal) count++;
        if (c == edgeVal) count++;
        return count >= 2;
    }

    private int addVertex(Map<Long, Integer> vertexMap, int x, int y) {
        long key = ((long) y << 32) | (x & 0xFFFFFFFFL);
        return vertexMap.computeIfAbsent(key, k -> vertexMap.size());
    }

    private void extractTriangle(int ax, int ay, int bx, int by, int cx, int cy,
                                  float maxError,
                                  Map<Long, Integer> vertexMap, List<int[]> triangles) {
        int mx = (ax + bx) >> 1;
        int my = (ay + by) >> 1;

        // Check if we can subdivide (hypotenuse endpoints not adjacent)
        boolean canSplit = Math.abs(ax - bx) > 1 || Math.abs(ay - by) > 1;

        if (canSplit) {
            boolean shouldSplit = false;

            // Check error threshold at midpoint
            if (errors[my * gridSize + mx] > maxError) {
                shouldSplit = true;
            }

            // Force-split if the triangle has a side along a tile edge.
            // This ensures ALL edge grid points become mesh vertices, so adjacent
            // tiles always share the exact same vertex positions on their common edge.
            if (twoOnEdge(ax, bx, cx, 0) || twoOnEdge(ax, bx, cx, gridSize - 1) ||
                twoOnEdge(ay, by, cy, 0) || twoOnEdge(ay, by, cy, gridSize - 1)) {
                shouldSplit = true;
            }

            // Force-split large triangles to ensure minimum mesh density
            // (needed so flat terrain still follows globe curvature)
            int maxSpan = (gridSize - 1) / 8;
            if (Math.abs(ax - bx) > maxSpan || Math.abs(ay - by) > maxSpan) {
                shouldSplit = true;
            }

            if (shouldSplit) {
                addVertex(vertexMap, mx, my);

                // Left child: hypotenuse (cx,cy)-(ax,ay), apex (mx,my)
                extractTriangle(cx, cy, ax, ay, mx, my, maxError, vertexMap, triangles);
                // Right child: hypotenuse (bx,by)-(cx,cy), apex (mx,my)
                extractTriangle(bx, by, cx, cy, mx, my, maxError, vertexMap, triangles);
                return;
            }
        }

        // Leaf triangle: emit with CCW winding
        int ia = addVertex(vertexMap, ax, ay);
        int ib = addVertex(vertexMap, bx, by);
        int ic = addVertex(vertexMap, cx, cy);
        // Cross product in grid coords: positive = CCW, negative = CW
        int cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross > 0) {
            triangles.add(new int[]{ia, ib, ic});
        } else {
            triangles.add(new int[]{ia, ic, ib});
        }
    }

}
