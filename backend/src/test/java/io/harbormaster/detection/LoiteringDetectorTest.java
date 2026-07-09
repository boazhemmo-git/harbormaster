package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TestTracks;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoiteringDetectorTest {

    private final Instant t0 = Instant.parse("2026-06-01T12:00:00Z");
    private LoiteringDetector detector;
    private List<Alert> alerts;

    @BeforeEach
    void setUp() {
        detector = new LoiteringDetector(new DetectionProperties(
                null, null,
                new DetectionProperties.Loitering(Duration.ofMinutes(10), 1.0, Duration.ofMinutes(15)),
                null));
        alerts = new ArrayList<>();
    }

    @Test
    void underwayStatusButStationaryAlerts() {
        VesselTrack track = stationaryVessel(340000001, 0, Duration.ofMinutes(12));

        detector.onSweep(List.of(track), t0, alerts::add);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().type()).isEqualTo(AlertType.LOITERING);
    }

    @Test
    void anchoredVesselIsLegitimatelyStationary() {
        VesselTrack track = stationaryVessel(340000002, 1, Duration.ofMinutes(12)); // at anchor

        detector.onSweep(List.of(track), t0, alerts::add);

        assertThat(alerts).isEmpty();
    }

    @Test
    void shortObservationWindowIsInconclusive() {
        VesselTrack track = stationaryVessel(340000003, 0, Duration.ofMinutes(4));

        detector.onSweep(List.of(track), t0, alerts::add);

        assertThat(alerts).isEmpty();
    }

    private VesselTrack stationaryVessel(int mmsi, int navStatus, Duration observedFor) {
        VesselTrack track = TestTracks.track(mmsi);
        int fixes = 6;
        for (int i = 0; i < fixes; i++) {
            Instant time = t0.minus(observedFor).plusSeconds(observedFor.toSeconds() * i / (fixes - 1));
            TestTracks.addFix(track, new Fix(time, 55.1, 6.2, 0.3, 0.0, 0, navStatus));
        }
        TestTracks.setState(track, TrackState.ACTIVE);
        return track;
    }
}
