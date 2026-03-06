package org.citydb.raster.io;

import org.citydb.raster.mesh.MeshResult;

import java.io.*;

/**
 * Writes Cesium quantized-mesh binary (.terrain) files.
 * Handles vertex quantization, delta + zigzag encoding, high-water-mark
 * index encoding, and the binary layout defined by the Cesium terrain spec.
 */
public class QuantizedMeshWriter {

    /**
     * Quantize mesh vertices and write a complete quantized-mesh .terrain file.
     *
     * @param filePath       output file path
     * @param centerX        tile center ECEF X
     * @param centerY        tile center ECEF Y
     * @param centerZ        tile center ECEF Z
     * @param bCenterX       bounding sphere center X
     * @param bCenterY       bounding sphere center Y
     * @param bCenterZ       bounding sphere center Z
     * @param radius         bounding sphere radius
     * @param minHeight      minimum elevation in the tile
     * @param maxHeight      maximum elevation in the tile
     * @param horizonX       horizon culling point X
     * @param horizonY       horizon culling point Y
     * @param horizonZ       horizon culling point Z
     * @param mesh           mesh result from a MeshStrategy
     * @param elevationData  2D elevation grid [x][y]
     * @param gridSize       side length of the elevation grid
     */
    public static void write(String filePath,
                             double centerX, double centerY, double centerZ,
                             double bCenterX, double bCenterY, double bCenterZ,
                             double radius, double minHeight, double maxHeight,
                             double horizonX, double horizonY, double horizonZ,
                             MeshResult mesh, double[][] elevationData, int gridSize) throws IOException {

        // Quantize vertices with delta + zigzag encoding
        Vertex[] vertexArray = quantizeVertices(mesh, elevationData, gridSize, minHeight, maxHeight);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(filePath))) {
            // Header
            dos.write(doubleToLittleEndian(centerX));
            dos.write(doubleToLittleEndian(centerY));
            dos.write(doubleToLittleEndian(centerZ));
            dos.write(floatToLittleEndian((float) minHeight));
            dos.write(floatToLittleEndian((float) maxHeight));

            dos.write(doubleToLittleEndian(bCenterX));
            dos.write(doubleToLittleEndian(bCenterY));
            dos.write(doubleToLittleEndian(bCenterZ));
            dos.write(doubleToLittleEndian(radius));

            dos.write(doubleToLittleEndian(horizonX));
            dos.write(doubleToLittleEndian(horizonY));
            dos.write(doubleToLittleEndian(horizonZ));

            // Vertex data
            int vertexNum = vertexArray.length;
            dos.write(intToLittleEndian(vertexNum));
            for (int i = 0; i < vertexNum; i++) {
                dos.write(shortToLittleEndian(vertexArray[i].u));
            }
            for (int i = 0; i < vertexNum; i++) {
                dos.write(shortToLittleEndian(vertexArray[i].v));
            }
            for (int i = 0; i < vertexNum; i++) {
                dos.write(shortToLittleEndian(vertexArray[i].h));
            }

            // Triangle indices (high-water-mark encoded)
            int[] encodedIndices = encodeHighWaterMark(mesh.triangles);
            dos.write(intToLittleEndian(encodedIndices.length / 3));
            for (int index : encodedIndices) {
                dos.write(shortToLittleEndian((short) index));
            }

            // Edge indices
            writeEdge(dos, mesh.westEdge);
            writeEdge(dos, mesh.southEdge);
            writeEdge(dos, mesh.eastEdge);
            writeEdge(dos, mesh.northEdge);
        }
    }

    static Vertex[] quantizeVertices(MeshResult mesh, double[][] elevationData,
                                     int gridSize, double minHeight, double maxHeight) {
        int lastU = 0, lastV = 0, lastH = 0;
        Vertex[] vertexArray = new Vertex[mesh.vertexCount];

        for (int i = 0; i < mesh.vertexCount; i++) {
            int x = mesh.gridIndices[i * 2];
            int y = mesh.gridIndices[i * 2 + 1];
            double elevation = elevationData[x][y];

            short u = (short) Math.round(((double) x / (gridSize - 1)) * 32767);
            short v = (short) Math.round(((double) y / (gridSize - 1)) * 32767);
            short h = (short) Math.round(((elevation - minHeight) / (maxHeight - minHeight)) * 32767);

            short a = zigZagEncode((short) (u - lastU));
            short b = zigZagEncode((short) (v - lastV));
            short c = zigZagEncode((short) (h - lastH));

            lastU = u;
            lastV = v;
            lastH = h;

            vertexArray[i] = new Vertex(a, b, c);
        }
        return vertexArray;
    }

    private static void writeEdge(DataOutputStream dos, int[] edgeIndices) throws IOException {
        dos.write(intToLittleEndian(edgeIndices.length));
        for (int index : edgeIndices) {
            dos.write(shortToLittleEndian((short) index));
        }
    }

    static short zigZagEncode(short value) {
        return (short) (((value << 1) ^ (value >> 15)) & 0xFFFF);
    }

    static int[] encodeHighWaterMark(int[] values) {
        int[] encoded = new int[values.length];
        int highest = 0;
        for (int i = 0; i < values.length; ++i) {
            int value = values[i];
            encoded[i] = highest - value;
            if (value == highest) {
                ++highest;
            }
        }
        return encoded;
    }

    static byte[] shortToLittleEndian(short value) {
        byte[] bytes = new byte[2];
        bytes[0] = (byte) (value & 0xff);
        bytes[1] = (byte) ((value >> 8) & 0xff);
        return bytes;
    }

    static byte[] intToLittleEndian(int value) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (value & 0xFF);
        bytes[1] = (byte) ((value >> 8) & 0xFF);
        bytes[2] = (byte) ((value >> 16) & 0xFF);
        bytes[3] = (byte) ((value >> 24) & 0xFF);
        return bytes;
    }

    static byte[] doubleToLittleEndian(double value) {
        long longBits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((longBits >> (i * 8)) & 0xFF);
        }
        return bytes;
    }

    static byte[] floatToLittleEndian(float value) {
        int intBits = Float.floatToIntBits(value);
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (intBits & 0xFF);
        bytes[1] = (byte) ((intBits >> 8) & 0xFF);
        bytes[2] = (byte) ((intBits >> 16) & 0xFF);
        bytes[3] = (byte) ((intBits >> 24) & 0xFF);
        return bytes;
    }

    static class Vertex {
        public final short u;
        public final short v;
        public final short h;

        Vertex(short u, short v, short h) {
            this.u = u;
            this.v = v;
            this.h = h;
        }
    }
}
