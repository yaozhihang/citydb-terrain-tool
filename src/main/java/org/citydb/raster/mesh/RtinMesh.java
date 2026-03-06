package org.citydb.raster.mesh;

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
    public MeshResult generateMesh(int gridSize, double[] terrain, float maxError, int maxTriangleSpan) {
        // Create a dedicated instance to ensure thread safety
        // (this object may be shared across threads)
        RtinMesh instance = new RtinMesh(gridSize, terrain);
        instance.computeErrors();
        return instance.extractMesh(maxError, maxTriangleSpan);
    }

    /**
     * Bottom-up error computation for all triangles.
     * Processes both root triangles. Errors are stored at each triangle's
     * hypotenuse midpoint and propagated upward (parent gets max of own error and children).
     */
    public void computeErrors() {
        Arrays.fill(errors, 0);

        // Iterate until the error array converges. The recursive bottom-up
        // computation processes left subtrees before right subtrees at every
        // level, so diamond pairs that cross subtree boundaries miss each
        // other's contributions. Each pass propagates one more level of
        // cross-subtree errors via Math.max (which only increases values),
        // guaranteeing convergence. Typically 2-4 passes suffice.
        for (int pass = 0; pass < 20; pass++) {
            double[] prev = Arrays.copyOf(errors, errors.length);

            // Root triangle 0: bottom-left half
            computeErrorsRecursive(
                    0, gridSize - 1,
                    gridSize - 1, 0,
                    0, 0);

            // Root triangle 1: top-right half
            computeErrorsRecursive(
                    gridSize - 1, 0,
                    0, gridSize - 1,
                    gridSize - 1, gridSize - 1);

            if (Arrays.equals(errors, prev)) break;
        }
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

        // Force-split at tile-edge midpoints by setting error to max.
        // This ensures all edge grid points become mesh vertices (for seamless tile
        // stitching) AND propagates upward so diamond-pair consistency is maintained,
        // preventing T-junction gaps.
        if (mx == 0 || mx == gridSize - 1 || my == 0 || my == gridSize - 1) {
            error = Double.MAX_VALUE;
        }

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
    public MeshResult extractMesh(float maxError, int maxTriangleSpan) {
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
                maxError, maxTriangleSpan, vertexMap, triangles);

        // Root triangle 1: top-right
        extractTriangle(
                gridSize - 1, 0,
                0, gridSize - 1,
                gridSize - 1, gridSize - 1,
                maxError, maxTriangleSpan, vertexMap, triangles);

        // Re-index vertices by first-appearance order in the triangle list.
        // High-water-mark encoding requires that vertex N first appears in the
        // flat index array exactly when the running "highest" counter equals N.
        // By numbering vertices in the order they first appear, this is guaranteed.
        int numVertices = vertexMap.size();
        int numTriangles = triangles.size();

        // Step 1: map insertion-order indices → scanline-order indices
        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(vertexMap.entrySet());
        entries.sort((a, b) -> {
            int ay = (int) (a.getKey() >> 32);
            int axx = (int) (a.getKey() & 0xFFFFFFFFL);
            int by2 = (int) (b.getKey() >> 32);
            int bxx = (int) (b.getKey() & 0xFFFFFFFFL);
            if (ay != by2) return Integer.compare(ay, by2);
            return Integer.compare(axx, bxx);
        });
        int[] toScanline = new int[numVertices];
        for (int i = 0; i < entries.size(); i++) {
            toScanline[entries.get(i).getValue()] = i;
        }

        // Step 2: re-index triangles to scanline order & sort by min vertex
        int[][] triScanline = new int[numTriangles][3];
        for (int i = 0; i < numTriangles; i++) {
            int[] tri = triangles.get(i);
            triScanline[i][0] = toScanline[tri[0]];
            triScanline[i][1] = toScanline[tri[1]];
            triScanline[i][2] = toScanline[tri[2]];
        }
        Arrays.sort(triScanline, (a, b) -> {
            int minA = Math.min(a[0], Math.min(a[1], a[2]));
            int minB = Math.min(b[0], Math.min(b[1], b[2]));
            return Integer.compare(minA, minB);
        });

        // Step 3: assign final vertex indices by first-appearance in sorted triangles
        int[] scanlineToFinal = new int[numVertices];
        Arrays.fill(scanlineToFinal, -1);
        int nextIdx = 0;
        for (int[] tri : triScanline) {
            for (int v : tri) {
                if (scanlineToFinal[v] == -1) {
                    scanlineToFinal[v] = nextIdx++;
                }
            }
        }

        // Step 4: build final triangle index array with first-appearance indices
        int[] triArray = new int[numTriangles * 3];
        for (int i = 0; i < numTriangles; i++) {
            triArray[i * 3]     = scanlineToFinal[triScanline[i][0]];
            triArray[i * 3 + 1] = scanlineToFinal[triScanline[i][1]];
            triArray[i * 3 + 2] = scanlineToFinal[triScanline[i][2]];
        }

        // Step 5: build vertex grid-index array in final order
        // finalToScanline[finalIdx] = scanlineIdx
        int[] finalToScanline = new int[numVertices];
        for (int s = 0; s < numVertices; s++) {
            finalToScanline[scanlineToFinal[s]] = s;
        }
        int[] gridIndices = new int[numVertices * 2];
        for (int f = 0; f < numVertices; f++) {
            long key = entries.get(finalToScanline[f]).getKey();
            gridIndices[f * 2]     = (int) (key & 0xFFFFFFFFL);  // x
            gridIndices[f * 2 + 1] = (int) (key >> 32);           // y
        }

        // Step 6: collect edge vertices (sorted ascending by final index)
        List<Integer> westEdge = new ArrayList<>();
        List<Integer> eastEdge = new ArrayList<>();
        List<Integer> southEdge = new ArrayList<>();
        List<Integer> northEdge = new ArrayList<>();

        for (int f = 0; f < numVertices; f++) {
            int x = gridIndices[f * 2];
            int y = gridIndices[f * 2 + 1];
            if (x == 0) westEdge.add(f);
            if (x == gridSize - 1) eastEdge.add(f);
            if (y == 0) southEdge.add(f);
            if (y == gridSize - 1) northEdge.add(f);
        }
        // Cesium requires edge vertices in geographic order:
        // west/east edges: south to north (ascending y)
        // south/north edges: west to east (ascending x)
        westEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2 + 1]));
        eastEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2 + 1]));
        southEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2]));
        northEdge.sort(Comparator.comparingInt(f -> gridIndices[f * 2]));

        return new MeshResult(
                numVertices,
                gridIndices,
                numTriangles,
                triArray,
                westEdge.stream().mapToInt(Integer::intValue).toArray(),
                southEdge.stream().mapToInt(Integer::intValue).toArray(),
                eastEdge.stream().mapToInt(Integer::intValue).toArray(),
                northEdge.stream().mapToInt(Integer::intValue).toArray()
        );
    }

    private int addVertex(Map<Long, Integer> vertexMap, int x, int y) {
        long key = ((long) y << 32) | (x & 0xFFFFFFFFL);
        return vertexMap.computeIfAbsent(key, k -> vertexMap.size());
    }

    private void extractTriangle(int ax, int ay, int bx, int by, int cx, int cy,
                                  float maxError, int maxTriangleSpan,
                                  Map<Long, Integer> vertexMap, List<int[]> triangles) {
        int mx = (ax + bx) >> 1;
        int my = (ay + by) >> 1;

        // Check if we can subdivide (hypotenuse endpoints not adjacent)
        boolean canSplit = Math.abs(ax - bx) > 1 || Math.abs(ay - by) > 1;

        if (canSplit) {
            boolean shouldSplit = false;

            // Check error threshold at midpoint
            // (edge midpoints have forced MAX_VALUE error from computeErrors,
            //  ensuring all edge grid points become vertices for tile stitching)
            if (errors[my * gridSize + mx] > maxError) {
                shouldSplit = true;
            }

            // Force-split large triangles to ensure minimum mesh density
            // (needed so flat terrain still follows globe curvature)
            if (Math.abs(ax - bx) > maxTriangleSpan || Math.abs(ay - by) > maxTriangleSpan) {
                shouldSplit = true;
            }

            if (shouldSplit) {
                addVertex(vertexMap, mx, my);

                // Left child: hypotenuse (cx,cy)-(ax,ay), apex (mx,my)
                extractTriangle(cx, cy, ax, ay, mx, my, maxError, maxTriangleSpan, vertexMap, triangles);
                // Right child: hypotenuse (bx,by)-(cx,cy), apex (mx,my)
                extractTriangle(bx, by, cx, cy, mx, my, maxError, maxTriangleSpan, vertexMap, triangles);
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
