package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TestTracks;
import io.harbormaster.tracking.VesselTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KinematicAnomalyDetectorTest {

    private final Instant t0 = Instant.parse("2026-06-01T12:00:00Z");
    private KinematicAnomalyDetector detector;
    private List<Alert> alerts;

    @BeforeEach
    void setUp() {
        var properties = new DetectionProperties(
                new DetectionProperties.Kinematic(60, Duration.ofMinutes(5)),
                null, null, null);
        detector = new KinematicAnomalyDetector(properties);
        alerts = new ArrayList<>();
    }

    @Test
    void teleportingVesselRaisesAlert() {
        VesselTrack track = TestTracks.track(244000001);
        // ~55 nm jump in 60 s => ~3300 kn implied
        Fix previous = fix(t0, 52.0, 3.0, 12.0);
        Fix current = fix(t0.plusSeconds(60), 52.9, 3.2, 12.0);

        detector.onFix(track, previous, current, alerts::add);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().type()).isEqualTo(AlertType.KINEMATIC_ANOMALY);
        assertThat(alerts.getFirst().severity()).isEqualTo(Alert.Severity.CRITICAL);
    }

    @Test
    void honestFastFerryDoesNotAlert() {
        VesselTrack track = TestTracks.track(244000002);
        // 40 kn reported, ~40 kn implied: 0.667 nm in 60 s ≈ 0.0111° lat
        Fix previous = fix(t0, 52.0, 3.0, 40.0);
        Fix current = fix(t0.plusSeconds(60), 52.0111, 3.0, 40.0);

        detector.onFix(track, previous, current, alerts::add);

        assertThat(alerts).isEmpty();
    }

    @Test
    void cooldownSuppressesAlertStorm() {
        VesselTrack track = TestTracks.track(244000003);
        detector.onFix(track, fix(t0, 52.0, 3.0, 5.0), fix(t0.plusSeconds(30), 52.5, 3.0, 5.0), alerts::add);
        detector.onFix(track, fix(t0.plusSeconds(30), 52.5, 3.0, 5.0),
                fix(t0.plusSeconds(60), 53.0, 3.0, 5.0), alerts::add);

        assertThat(alerts).hasSize(1);
    }

    @Test
    void subIntervalJitterIsIgnored() {
        VesselTrack track = TestTracks.track(244000004);
        detector.onFix(track, fix(t0, 52.0, 3.0, 5.0), fix(t0.plusSeconds(2), 52.5, 3.0, 5.0), alerts::add);

        assertThat(alerts).isEmpty();
    }

    private static Fix fix(Instant time, double lat, double lon, double sog) {
        return new Fix(time, lat, lon, sog, 0.0, 0, 0);
    }
}
