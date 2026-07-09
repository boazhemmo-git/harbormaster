package io.harbormaster.detection;

/** Great-circle math shared by the detectors. */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private Geo() {
    }

    /** Haversine distance in metres. */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double metersToNauticalMiles(double meters) {
        return meters / 1852.0;
    }

    public static double impliedSpeedKnots(double meters, double seconds) {
        return seconds <= 0 ? Double.POSITIVE_INFINITY : metersToNauticalMiles(meters) / (seconds / 3600.0);
    }
}
