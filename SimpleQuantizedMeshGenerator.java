package org.citydb.raster;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class QuantizedMeshGenerator {

    public static class Vertex {
        public int x, y, z;

        public Vertex(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class QuantizedMesh {
        public byte[] vertexData;
        public List<int[]> indices;

        public QuantizedMesh(byte[] vertexData, List<int[]> indices) {
            this.vertexData = vertexData;
            this.indices = indices;
        }
    }

    public static QuantizedMesh generateMesh(float[][] heightMap, int gridWidth, int gridHeight, int quantizationLevels) {
        int vertexCount = gridWidth * gridHeight;
        ByteBuffer buffer = ByteBuffer.allocate(vertexCount * 6).order(ByteOrder.LITTLE_ENDIAN);
        List<int[]> indices = new ArrayList<>();

        float maxZ = Float.MIN_VALUE;
        float minZ = Float.MAX_VALUE;

        // Find min and max Z values
        for (float[] row : heightMap) {
            for (float z : row) {
                maxZ = Math.max(maxZ, z);
                minZ = Math.min(minZ, z);
            }
        }

        float rangeZ = maxZ - minZ;

        // Generate vertices
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                int quantizedX = (int) ((float) x / (gridWidth - 1) * (quantizationLevels - 1));
                int quantizedY = (int) ((float) y / (gridHeight - 1) * (quantizationLevels - 1));
                int quantizedZ = (int) (((heightMap[y][x] - minZ) / rangeZ) * (quantizationLevels - 1));

                buffer.putShort((short) quantizedX);
                buffer.putShort((short) quantizedY);
                buffer.putShort((short) quantizedZ);
            }
        }

        // Generate triangle indices
        for (int y = 0; y < gridHeight - 1; y++) {
            for (int x = 0; x < gridWidth - 1; x++) {
                int topLeft = y * gridWidth + x;
                int topRight = topLeft + 1;
                int bottomLeft = (y + 1) * gridWidth + x;
                int bottomRight = bottomLeft + 1;

                indices.add(new int[]{topLeft, bottomLeft, topRight});
                indices.add(new int[]{topRight, bottomLeft, bottomRight});
            }
        }

        return new QuantizedMesh(buffer.array(), indices);
    }

    public static void main(String[] args) {
        int width = 10;
        int height = 10;
        int quantizationLevels = 32768; // 16-bit quantization

        // Generate a sample height map (e.g., a sine wave pattern)
        float[][] heightMap = new float[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                heightMap[y][x] = (float) Math.sin(x * 0.5) * (float) Math.cos(y * 0.5);
            }
        }

        QuantizedMesh mesh = generateMesh(heightMap, width, height, quantizationLevels);

        // Print vertex data and indices for verification
        System.out.println("Vertex Data (in bytes): " + mesh.vertexData.length);
        System.out.println("Number of Triangles: " + mesh.indices.size());

        for (int[] triangle : mesh.indices) {
            System.out.println("Triangle: " + triangle[0] + ", " + triangle[1] + ", " + triangle[2]);
        }
    }
}
