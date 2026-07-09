package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.VesselTrack;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flags physically implausible movement between consecutive fixes — the
 * signature of GNSS/AIS position spoofing or identity collisions (two
 * transmitters sharing an MMSI).
 *
 * <p>The implied speed must exceed both an absolute ceiling and a multiple of
 * the vessel's own reported speed, so a fast ferry doing 40 kn does not
 * trigger on honest data, while a "teleporting" tanker does.
 */
@Component
public class KinematicAnomalyDetector implements AnomalyDetector {

    private static final double REPORTED_SPEED_TOLERANCE = 2.5;
    private static final long MIN_INTERVAL_SECONDS = 5;

    private final DetectionProperties.Kinematic config;

    public KinematicAnomalyDetector(DetectionProperties properties) {
        this.config = properties.kinematic();
    }

    @Override
    public void onFix(VesselTrack track, Fix previous, Fix current, Consumer<Alert> alerts) {
        if (previous == null) {
            return;
        }
        double seconds = Duration.between(previous.time(), current.time()).toMillis() / 1000.0;
        if (seconds < MIN_INTERVAL_SECONDS) {
            return; // sub-interval jitter is not evidence
        }
        double meters = Geo.distanceM(previous.lat(), previous.lon(), current.lat(), current.lon());
        double impliedKn = Geo.impliedSpeedKnots(meters, seconds);

        double reportedKn = Double.isNaN(current.sogKn()) ? 0 : current.sogKn();
        double threshold = Math.max(config.maxSpeedKnots(), reportedKn * REPORTED_SPEED_TOLERANCE);
        if (impliedKn <= threshold) {
            return;
        }
        if (!track.tryArmCooldown("kinematic", current.time(), config.cooldown().toSeconds())) {
            return;
        }

        var severity = impliedKn > 200 ? Alert.Severity.CRITICAL : Alert.Severity.WARNING;
        alerts.accept(Alert.of(
                current.time(), AlertType.KINEMATIC_ANOMALY, severity,
                track.mmsi(), track.bestName(),
                current.lat(), current.lon(),
                "%s jumped %.1f nm in %.0f s (implied %.0f kn, reported %.1f kn)".formatted(
                        track.bestName(), Geo.metersToNauticalMiles(meters), seconds, impliedKn, reportedKn),
                Map.of(
                        "impliedSpeedKn", Math.round(impliedKn),
                        "reportedSpeedKn", reportedKn,
                        "jumpNm", Math.round(Geo.metersToNauticalMiles(meters) * 10) / 10.0,
                        "intervalSec", Math.round(seconds))));
    }
}
