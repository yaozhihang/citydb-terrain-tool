package org.citydb.terrain.util;

public class Cartesian3 {
    public double x;
    public double y;
    public double z;

    public Cartesian3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Cartesian3 subtract(Cartesian3 left, Cartesian3 right) {
        return new Cartesian3(left.x - right.x, left.y - right.y, left.z - right.z);
    }

    public static double dot(Cartesian3 left, Cartesian3 right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    public static Cartesian3 cross(Cartesian3 left, Cartesian3 right) {
        return new Cartesian3(
                left.y * right.z - left.z * right.y,
                left.z * right.x - left.x * right.z,
                left.x * right.y - left.y * right.x);
    }

    public static double magnitude(Cartesian3 cartesian) {
        return Math.sqrt(magnitudeSquared(cartesian));
    }

    public static double magnitudeSquared(Cartesian3 cartesian) {
        return cartesian.x * cartesian.x + cartesian.y * cartesian.y + cartesian.z * cartesian.z;
    }

    public static double computeMagnitude(Cartesian3 ellipsoid, Cartesian3 position, Cartesian3 scaledSpaceDirectionToPoint) {
        Cartesian3 scaledSpacePosition = new Cartesian3(
                position.x / ellipsoid.x, position.y / ellipsoid.y, position.z / ellipsoid.z);
        double magSq = Math.max(1.0, magnitudeSquared(scaledSpacePosition));
        double mag = Math.max(1.0, magnitude(scaledSpacePosition));
        Cartesian3 direction = new Cartesian3(
                scaledSpacePosition.x / mag, scaledSpacePosition.y / mag, scaledSpacePosition.z / mag);

        double cosAlpha = dot(direction, scaledSpaceDirectionToPoint);
        double sinAlpha = magnitude(cross(direction, scaledSpaceDirectionToPoint));
        double cosBeta = 1.0 / mag;
        double sinBeta = Math.sqrt(magSq - 1.0) * cosBeta;

        return 1.0 / (cosAlpha * cosBeta - sinAlpha * sinBeta);
    }

    public static Cartesian3 magnitudeToPoint(Cartesian3 scaledSpaceDirectionToPoint, double resultMagnitude) {
        if (resultMagnitude <= 0.0 || Double.isNaN(resultMagnitude) || Double.isInfinite(resultMagnitude)) {
            return new Cartesian3(0, 0, 0);
        }
        return new Cartesian3(
                scaledSpaceDirectionToPoint.x * resultMagnitude,
                scaledSpaceDirectionToPoint.y * resultMagnitude,
                scaledSpaceDirectionToPoint.z * resultMagnitude);
    }

    public static Cartesian3 computeHorizonCullingPoint(Cartesian3 directionToPoint, Cartesian3[] positions) {
        Cartesian3 ellipsoid = new Cartesian3(6378137.0, 6378137.0, 6356752.3142451793);
        Cartesian3 scaledDir = new Cartesian3(
                directionToPoint.x / ellipsoid.x,
                directionToPoint.y / ellipsoid.y,
                directionToPoint.z / ellipsoid.z);
        double mag = magnitude(scaledDir);
        scaledDir = new Cartesian3(scaledDir.x / mag, scaledDir.y / mag, scaledDir.z / mag);

        double resultMagnitude = 0.0;
        for (Cartesian3 position : positions) {
            double candidateMagnitude = computeMagnitude(ellipsoid, position, scaledDir);
            resultMagnitude = Math.max(resultMagnitude, candidateMagnitude);
        }

        return magnitudeToPoint(scaledDir, resultMagnitude);
    }
}
