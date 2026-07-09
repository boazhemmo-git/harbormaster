package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Flags vessels whose navigation status claims "under way" while their
 * observed movement says otherwise for a sustained window — drifting near a
 * pipeline, waiting for a rendezvous, or simply misconfigured equipment.
 *
 * <p>Port-service types (tugs, pilots) are excluded: idling is their job.
 */
@Component
public class LoiteringDetector implements AnomalyDetector {

    private final DetectionProperties.Loitering config;

    public LoiteringDetector(DetectionProperties properties) {
        this.config = properties.loitering();
    }

    @Override
    public void onSweep(Collection<VesselTrack> tracks, Instant now, Consumer<Alert> alerts) {
        for (VesselTrack track : tracks) {
            if (track.state() != TrackState.ACTIVE || track.info().isPortServiceType()) {
                continue;
            }
            List<Fix> fixes = track.recentFixes();
            if (fixes.size() < 5) {
                continue;
            }
            Fix newest = fixes.getLast();
            if (!newest.isUnderway() || newest.isStationaryStatus()) {
                continue;
            }
            if (!coversWindow(fixes, now)) {
                continue;
            }
            boolean allSlow = fixes.stream()
                    .filter(f -> !now.minus(config.window()).isAfter(f.time()))
                    .allMatch(f -> !Double.isNaN(f.sogKn()) && f.sogKn() <= config.maxSogKnots());
            if (!allSlow) {
                continue;
            }
            if (!track.tryArmCooldown("loitering", now, config.cooldown().toSeconds())) {
                continue;
            }
            alerts.accept(Alert.of(
                    now, AlertType.LOITERING, Alert.Severity.INFO,
                    track.mmsi(), track.bestName(),
                    newest.lat(), newest.lon(),
                    "%s reports underway but has been stationary for %d+ minutes".formatted(
                            track.bestName(), config.window().toMinutes()),
                    Map.of(
                            "windowMinutes", config.window().toMinutes(),
                            "navStatus", newest.navStatus())));
        }
    }

    /** The fix history must span the whole detection window to be conclusive. */
    private boolean coversWindow(List<Fix> fixes, Instant now) {
        Instant oldest = fixes.getFirst().time();
        return Duration.between(oldest, now).compareTo(config.window()) >= 0;
    }
}
