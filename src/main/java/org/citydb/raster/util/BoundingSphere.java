package org.citydb.raster.util;

import java.util.List;

public class BoundingSphere {

    // A class to represent a 3D point
    public static class Point3D {
        public double x, y, z;

        public Point3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        // Compute squared distance between two points
        double distanceSquared(Point3D other) {
            return (x - other.x) * (x - other.x) +
                    (y - other.y) * (y - other.y) +
                    (z - other.z) * (z - other.z);
        }
    }

    // A class to represent a sphere
    public static class Sphere {
        public Point3D center;
        public double radius;

        Sphere(Point3D center, double radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    // Compute the bounding sphere of a set of points
    public static Sphere computeBoundingSphere(List<Point3D> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Point list cannot be null or empty");
        }

        // Step 1: Find an initial point and the farthest point from it
        Point3D p0 = points.get(0);
        Point3D farthest = findFarthestPoint(p0, points);

        // Step 2: Find the farthest point from the previously found farthest point
        Point3D secondFarthest = findFarthestPoint(farthest, points);

        // Step 3: Use the two farthest points to define an initial sphere
        Point3D center = new Point3D(
                (farthest.x + secondFarthest.x) / 2.0,
                (farthest.y + secondFarthest.y) / 2.0,
                (farthest.z + secondFarthest.z) / 2.0
        );
        double radius = Math.sqrt(farthest.distanceSquared(center));

        // Step 4: Adjust the sphere to include all points
        for (Point3D point : points) {
            double distanceSquared = point.distanceSquared(center);
            if (distanceSquared > radius * radius) {
                // Expand the sphere to include the point
                double distance = Math.sqrt(distanceSquared);
                double newRadius = (radius + distance) / 2.0;
                double factor = (newRadius - radius) / distance;

                // Update the center
                center = new Point3D(
                        center.x + (point.x - center.x) * factor,
                        center.y + (point.y - center.y) * factor,
                        center.z + (point.z - center.z) * factor
                );
                radius = newRadius;
            }
        }

        return new Sphere(center, radius);
    }

    // Helper method to find the farthest point from a given point
    private static Point3D findFarthestPoint(Point3D reference, List<Point3D> points) {
        Point3D farthest = null;
        double maxDistanceSquared = -1;

        try {
            for (Point3D point : points) {
                double distanceSquared = reference.distanceSquared(point);
                if (distanceSquared > maxDistanceSquared) {
                    maxDistanceSquared = distanceSquared;
                    farthest = point;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return farthest;
    }
}
