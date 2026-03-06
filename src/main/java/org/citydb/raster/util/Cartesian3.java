package org.citydb.raster.util;

public class Cartesian3 {
    public double x;
    public double y;
    public double z;

    public Cartesian3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Cartesian3 subtract (Cartesian3 left, Cartesian3 right) {
        return new Cartesian3(left.x - right.x, left.y - right.y, left.z - right.z);
    }

    public static double dot(Cartesian3 left, Cartesian3 right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    public static Cartesian3 cross(Cartesian3 left, Cartesian3 right) {
        return new Cartesian3(left.y * right.z - left.z * right.y, left.z * right.x - left.x * right.z, left.x * right.y - left.y * right.x);
    }

    public static double magnitude(Cartesian3 cartesian) {
        return Math.sqrt(Cartesian3.magnitudeSquared(cartesian));
    }

    public static double magnitudeSquared(Cartesian3 cartesian) {
        return cartesian.x * cartesian.x + cartesian.y * cartesian.y + cartesian.z * cartesian.z;
    }

    public static double computeMagnitude(Cartesian3 ellipsoid, Cartesian3 position, Cartesian3 scaledSpaceDirectionToPoint) {
        Cartesian3 scaledSpacePosition = new Cartesian3(position.x / ellipsoid.x, position.y / ellipsoid.y, position.z / ellipsoid.z);
        double magnitudeSquared = Cartesian3.magnitudeSquared(scaledSpacePosition);
        double magnitude = Cartesian3.magnitude(scaledSpacePosition);
        Cartesian3 direction = new Cartesian3(scaledSpacePosition.x / magnitude, scaledSpacePosition.y / magnitude, scaledSpacePosition.z / magnitude);
        magnitudeSquared = Math.max(1.0, magnitudeSquared);
        magnitude = Math.max(1.0, magnitude);

        double cosAlpha = Cartesian3.dot(direction, scaledSpaceDirectionToPoint);
        double sinAlpha = Cartesian3.magnitude(Cartesian3.cross(direction, scaledSpaceDirectionToPoint));
        double cosBeta = 1.0 / magnitude;
        double sinBeta = Math.sqrt(magnitudeSquared - 1.0) * cosBeta;

        return 1.0 / (cosAlpha * cosBeta - sinAlpha * sinBeta);
    }

    public static Cartesian3 magnitudeToPoint(Cartesian3 scaledSpaceDirectionToPoint, double resultMagnitude) {
        if (resultMagnitude <= 0.0 || Double.isNaN(resultMagnitude) || Double.isInfinite(resultMagnitude)) {
            return new Cartesian3(0, 0, 0);
        }
        return new Cartesian3(
                scaledSpaceDirectionToPoint.x * resultMagnitude,
                scaledSpaceDirectionToPoint.y * resultMagnitude,
                scaledSpaceDirectionToPoint.z * resultMagnitude
        );
    }

    public static Cartesian3 computeHorizonCullingPoint(Cartesian3 directionToPoint, Cartesian3[] positions) {
        Cartesian3 ellipsoid = new Cartesian3(6378137.0, 6378137.0, 6356752.3142451793);
        Cartesian3 scaledSpaceDirectionToPoint = new Cartesian3(
                directionToPoint.x / ellipsoid.x,
                directionToPoint.y / ellipsoid.y,
                directionToPoint.z / ellipsoid.z);
        double magn= Cartesian3.magnitude(scaledSpaceDirectionToPoint);
        scaledSpaceDirectionToPoint = new Cartesian3(
                scaledSpaceDirectionToPoint.x / magn,
                scaledSpaceDirectionToPoint.y / magn,
                scaledSpaceDirectionToPoint.z / magn);

        double resultMagnitude = 0.0;
        for (Cartesian3 position : positions) {
            double candidateMagnitude = computeMagnitude(ellipsoid, position, scaledSpaceDirectionToPoint);
            resultMagnitude = Math.max(resultMagnitude, candidateMagnitude);
        }

        return Cartesian3.magnitudeToPoint(scaledSpaceDirectionToPoint, resultMagnitude);
    }
}
