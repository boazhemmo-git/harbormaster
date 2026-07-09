package io.harbormaster.ais;

/**
 * A vessel position fix — Class A reports (types 1/2/3) and Class B reports
 * (types 18/19) normalized to one shape.
 *
 * <p>"Not available" sentinel values from the wire (lon 181°, lat 91°,
 * SOG 1023, COG 3600, heading 511) are mapped to {@link Double#NaN} / -1 so
 * downstream code tests {@link #hasPosition()} instead of remembering magic
 * numbers.
 *
 * @param navStatus ITU navigation status (0 = under way using engine,
 *                  1 = at anchor, 5 = moored, ...); -1 for Class B, which does
 *                  not transmit one
 * @param heading   true heading in degrees, or -1 when unavailable
 * @param name      vessel name (type 19 extended Class B only, else null)
 */
public record PositionReport(
        int type,
        int mmsi,
        int navStatus,
        double speedOverGroundKn,
        double longitude,
        double latitude,
        double courseOverGroundDeg,
        int heading,
        int utcSecond,
        String name) implements AisMessage {

    public boolean hasPosition() {
        return !Double.isNaN(longitude) && !Double.isNaN(latitude);
    }

    public boolean hasSpeed() {
        return !Double.isNaN(speedOverGroundKn);
    }
}
