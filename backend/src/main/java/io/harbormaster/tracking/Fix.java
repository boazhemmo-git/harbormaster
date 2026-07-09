package io.harbormaster.tracking;

import java.time.Instant;

/**
 * A single accepted position observation for a vessel.
 *
 * @param sogKn      speed over ground in knots (NaN when not transmitted)
 * @param cogDeg     course over ground in degrees (NaN when not transmitted)
 * @param heading    true heading in degrees, -1 when not transmitted
 * @param navStatus  ITU navigation status, -1 for Class B transponders
 */
public record Fix(
        Instant time,
        double lat,
        double lon,
        double sogKn,
        double cogDeg,
        int heading,
        int navStatus) {

    public boolean isUnderway() {
        // Status 0 = under way using engine, 8 = under way sailing.
        // Class B (-1) is treated as underway when it is actually moving.
        return navStatus == 0 || navStatus == 8 || (navStatus == -1 && !Double.isNaN(sogKn) && sogKn >= 1.0);
    }

    public boolean isStationaryStatus() {
        return navStatus == 1 || navStatus == 5 || navStatus == 6; // anchored / moored / aground
    }
}
