package org.citydb.raster;

import java.util.*;

/**
 * Delaunay triangulation-based adaptive mesh generator.
 * Uses Bowyer-Watson incremental algorithm with greedy vertex insertion
 * based on interpolation error. Vertices are selected to keep the maximum
 * interpolation error below the threshold while respecting triangle span limits.
 */
public class DelaunayMesh implements MeshStrategy {

    @Override
    public MeshResult generateMesh(int gridSize, double[] terrain, float maxError, int maxTriangleSpan) {
        List<double[]> pts = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();
        Map<Long, Integer> gridToIdx = new HashMap<>();

        // Super-triangle enclosing the entire grid (indices 0, 1, 2)
        double m = gridSize * 4.0;
        pts.add(new double[]{-m, -m});
        pts.add(new double[]{m * 3, -m});
        pts.add(new double[]{gridSize / 2.0, m * 3});
        tris.add(new int[]{0, 1, 2});

        // Insert all edge grid points (required for seamless tile stitching)
        for (int i = 0; i < gridSize; i++) {
            insertGridPt(pts, tris, gridToIdx, i, 0);
            insertGridPt(pts, tris, gridToIdx, i, gridSize - 1);
        }
        for (int i = 1; i < gridSize - 1; i++) {
            insertGridPt(pts, tris, gridToIdx, 0, i);
            insertGridPt(pts, tris, gridToIdx, gridSize - 1, i);
        }

        // Seed interior with regular grid to enforce maxTriangleSpan
        int step = Math.max(1, maxTriangleSpan);
        for (int y = step; y < gridSize - 1; y += step) {
            for (int x = step; x < gridSize - 1; x += step) {
                insertGridPt(pts, tris, gridToIdx, x, y);
            }
        }

        // Greedy error-based insertion: repeatedly insert the grid point
        // with the highest interpolation error until all errors are within threshold
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        for (int[] tri : tris) {
            if (tri[0] < 3 || tri[1] < 3 || tri[2] < 3) continue;
            enqueueWorstPoint(pq, pts, tri, gridToIdx, terrain, gridSize);
        }

        while (!pq.isEmpty()) {
            double[] entry = pq.poll();
            if (entry[0] <= maxError) break;

            int gx = (int) entry[1], gy = (int) entry[2];
            long key = packKey(gx, gy);
            if (gridToIdx.containsKey(key)) continue;

            List<int[]> newTris = insertGridPt(pts, tris, gridToIdx, gx, gy);
            for (int[] tri : newTris) {
                if (tri[0] < 3 || tri[1] < 3 || tri[2] < 3) continue;
                enqueueWorstPoint(pq, pts, tri, gridToIdx, terrain, gridSize);
            }
        }

        // Remove super-triangle faces
        tris.removeIf(tri -> tri[0] < 3 || tri[1] < 3 || tri[2] < 3);

        return buildResult(pts, tris, gridSize);
    }

    // ── Bowyer-Watson incremental Delaunay triangulation ──────────────

    private List<int[]> insertGridPt(List<double[]> pts, List<int[]> tris,
                                     Map<Long, Integer> gridToIdx, int gx, int gy) {
        long key = packKey(gx, gy);
        if (gridToIdx.containsKey(key)) return Collections.emptyList();
        int idx = pts.size();
        pts.add(new double[]{gx, gy});
        gridToIdx.put(key, idx);
        return insertPoint(pts, tris, idx);
    }

    private List<int[]> insertPoint(List<double[]> pts, List<int[]> tris, int pIdx) {
        double px = pts.get(pIdx)[0], py = pts.get(pIdx)[1];

        // Partition triangles by circumcircle test
        List<int[]> bad = new ArrayList<>();
        List<int[]> good = new ArrayList<>();
        for (int[] tri : tris) {
            if (inCircumcircle(pts, tri, px, py)) bad.add(tri);
            else good.add(tri);
        }

        // Boundary polygon = unshared edges of bad triangles
        List<int[]> boundary = new ArrayList<>();
        for (int[] tri : bad) {
            for (int e = 0; e < 3; e++) {
                int a = tri[e], b = tri[(e + 1) % 3];
                boolean shared = false;
                for (int[] other : bad) {
                    if (other == tri) continue;
                    if (sharesEdge(other, a, b)) { shared = true; break; }
                }
                if (!shared) boundary.add(new int[]{a, b});
            }
        }

        // Create new triangles from each boundary edge to the new point
        List<int[]> newTris = new ArrayList<>();
        for (int[] edge : boundary) {
            int[] t = {edge[0], edge[1], pIdx};
            // Ensure CCW winding
            if (cross2d(pts.get(t[0]), pts.get(t[1]), pts.get(t[2])) < 0) {
                int tmp = t[0]; t[0] = t[1]; t[1] = tmp;
            }
            newTris.add(t);
        }

        tris.clear();
        tris.addAll(good);
        tris.addAll(newTris);
        return newTris;
    }

    // ── Geometric predicates ─────────────────────────────────────────

    /** Returns true if point (px,py) lies inside the circumcircle of the CCW triangle. */
    private boolean inCircumcircle(List<double[]> pts, int[] tri, double px, double py) {
        double ax = pts.get(tri[0])[0] - px, ay = pts.get(tri[0])[1] - py;
        double bx = pts.get(tri[1])[0] - px, by = pts.get(tri[1])[1] - py;
        double cx = pts.get(tri[2])[0] - px, cy = pts.get(tri[2])[1] - py;
        double aa = ax * ax + ay * ay;
        double bb = bx * bx + by * by;
        double cc = cx * cx + cy * cy;
        return ax * (by * cc - cy * bb)
             - ay * (bx * cc - cx * bb)
             + aa * (bx * cy - by * cx) > 0;
    }

    private boolean sharesEdge(int[] tri, int a, int b) {
        for (int e = 0; e < 3; e++) {
            int ta = tri[e], tb = tri[(e + 1) % 3];
            if ((ta == a && tb == b) || (ta == b && tb == a)) return true;
        }
        return false;
    }

    /** Signed area * 2 of triangle (a, b, c). Positive = CCW. */
    private static double cross2d(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    // ── Greedy error computation ─────────────────────────────────────

    /**
     * Find the uninserted grid point inside a triangle with the highest
     * interpolation error and add it to the priority queue.
     */
    private void enqueueWorstPoint(PriorityQueue<double[]> pq, List<double[]> pts,
                                   int[] tri, Map<Long, Integer> gridToIdx,
                                   double[] terrain, int gridSize) {
        double[] v0 = pts.get(tri[0]), v1 = pts.get(tri[1]), v2 = pts.get(tri[2]);
        double area = cross2d(v0, v1, v2);
        if (Math.abs(area) < 1e-10) return;

        double z0 = terrain[(int) v0[1] * gridSize + (int) v0[0]];
        double z1 = terrain[(int) v1[1] * gridSize + (int) v1[0]];
        double z2 = terrain[(int) v2[1] * gridSize + (int) v2[0]];

        int minX = Math.max(0, (int) Math.floor(Math.min(v0[0], Math.min(v1[0], v2[0]))));
        int maxX = Math.min(gridSize - 1, (int) Math.ceil(Math.max(v0[0], Math.max(v1[0], v2[0]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(v0[1], Math.min(v1[1], v2[1]))));
        int maxY = Math.min(gridSize - 1, (int) Math.ceil(Math.max(v0[1], Math.max(v1[1], v2[1]))));

        double bestErr = 0;
        int bestX = -1, bestY = -1;

        for (int gy = minY; gy <= maxY; gy++) {
            for (int gx = minX; gx <= maxX; gx++) {
                if (gridToIdx.containsKey(packKey(gx, gy))) continue;

                // Barycentric coordinates
                double w0 = ((v1[0] - gx) * (v2[1] - gy) - (v1[1] - gy) * (v2[0] - gx)) / area;
                double w1 = ((v2[0] - gx) * (v0[1] - gy) - (v2[1] - gy) * (v0[0] - gx)) / area;
                double w2 = 1.0 - w0 - w1;
                if (w0 < -1e-10 || w1 < -1e-10 || w2 < -1e-10) continue;

                double interpolated = w0 * z0 + w1 * z1 + w2 * z2;
                double actual = terrain[gy * gridSize + gx];
                double err = Math.abs(actual - interpolated);

                if (err > bestErr) {
                    bestErr = err;
                    bestX = gx;
                    bestY = gy;
                }
            }
        }

        if (bestX >= 0) {
            pq.add(new double[]{bestErr, bestX, bestY});
        }
    }

    // ── MeshResult construction ──────────────────────────────────────

    /**
     * Build a MeshResult with vertex ordering compatible with
     * high-water-mark index encoding and Cesium edge list requirements.
     */
    private MeshResult buildResult(List<double[]> pts, List<int[]> tris, int gridSize) {
        // Collect unique vertices referenced by triangles
        Set<Integer> used = new LinkedHashSet<>();
        for (int[] tri : tris) {
            used.add(tri[0]);
            used.add(tri[1]);
            used.add(tri[2]);
        }
        int numVertices = used.size();
        int numTriangles = tris.size();

        // Step 1: sort vertices into scanline order (ascending y, then x)
        List<Integer> vertexList = new ArrayList<>(used);
        vertexList.sort((a, b) -> {
            int cmp = Double.compare(pts.get(a)[1], pts.get(b)[1]);
            return cmp != 0 ? cmp : Double.compare(pts.get(a)[0], pts.get(b)[0]);
        });

        Map<Integer, Integer> toScanline = new HashMap<>();
        for (int i = 0; i < vertexList.size(); i++) {
            toScanline.put(vertexList.get(i), i);
        }

        // Step 2: re-index triangles to scanline order & sort by min vertex
        int[][] triScanline = new int[numTriangles][3];
        for (int i = 0; i < numTriangles; i++) {
            int[] tri = tris.get(i);
            triScanline[i][0] = toScanline.get(tri[0]);
            triScanline[i][1] = toScanline.get(tri[1]);
            triScanline[i][2] = toScanline.get(tri[2]);
        }
        Arrays.sort(triScanline, (a, b) -> {
            int minA = Math.min(a[0], Math.min(a[1], a[2]));
            int minB = Math.min(b[0], Math.min(b[1], b[2]));
            return Integer.compare(minA, minB);
        });

        // Step 3: assign final indices by first appearance in sorted triangles
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

        // Step 4: build triangle index array
        int[] triArray = new int[numTriangles * 3];
        for (int i = 0; i < numTriangles; i++) {
            triArray[i * 3]     = scanlineToFinal[triScanline[i][0]];
            triArray[i * 3 + 1] = scanlineToFinal[triScanline[i][1]];
            triArray[i * 3 + 2] = scanlineToFinal[triScanline[i][2]];
        }

        // Step 5: build vertex grid-index array in final order
        int[] finalToScanline = new int[numVertices];
        for (int s = 0; s < numVertices; s++) {
            finalToScanline[scanlineToFinal[s]] = s;
        }
        int[] gridIndices = new int[numVertices * 2];
        for (int f = 0; f < numVertices; f++) {
            int oldIdx = vertexList.get(finalToScanline[f]);
            gridIndices[f * 2]     = (int) pts.get(oldIdx)[0];
            gridIndices[f * 2 + 1] = (int) pts.get(oldIdx)[1];
        }

        // Step 6: collect edge vertices in geographic order
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

    private static long packKey(int x, int y) {
        return ((long) y << 32) | (x & 0xFFFFFFFFL);
    }
}
