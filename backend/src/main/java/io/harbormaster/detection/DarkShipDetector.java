package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Raises an alert when a vessel that was underway stops transmitting long
 * enough for its track to be declared {@link TrackState#LOST} — consistent
 * with a deliberately disabled transponder ("going dark"), the primary
 * evasion pattern for sanctioned or illegally fishing vessels.
 *
 * <p>Cargo vessels and tankers escalate to CRITICAL; they are the types with
 * the strongest sanctions-evasion incentive. Note that shore-station coverage
 * gaps produce the same observable, so alerts are evidence to investigate,
 * not verdicts — see the "honest limits" section of the README.
 */
@Component
public class DarkShipDetector implements AnomalyDetector {

    private final DetectionProperties.DarkShip config;

    public DarkShipDetector(DetectionProperties properties) {
        this.config = properties.darkShip();
    }

    @Override
    public void onStateChange(VesselTrack track, TrackState from, TrackState to, Consumer<Alert> alerts) {
        if (to != TrackState.LOST) {
            return;
        }
        Fix last = track.latestFix();
        if (last == null || Double.isNaN(last.sogKn()) || last.sogKn() < config.minSogKnots()) {
            return; // moored/anchored vessels stop transmitting legitimately
        }
        var severity = track.info().isHighInterestType() ? Alert.Severity.CRITICAL : Alert.Severity.WARNING;
        alerts.accept(Alert.of(
                Instant.now(), AlertType.AIS_GAP, severity,
                track.mmsi(), track.bestName(),
                last.lat(), last.lon(),
                "%s went dark while underway at %.1f kn".formatted(track.bestName(), last.sogKn()),
                Map.of(
                        "lastSeenAt", last.time().toString(),
                        "lastSogKn", last.sogKn(),
                        "shipType", track.info().shipType())));
    }
}
