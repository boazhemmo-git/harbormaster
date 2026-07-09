package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.TestTracks;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselInfo;
import io.harbormaster.tracking.VesselTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DarkShipDetectorTest {

    private final Instant t0 = Instant.parse("2026-06-01T12:00:00Z");
    private DarkShipDetector detector;
    private List<Alert> alerts;

    @BeforeEach
    void setUp() {
        detector = new DarkShipDetector(new DetectionProperties(
                null, new DetectionProperties.DarkShip(3.0), null, null));
        alerts = new ArrayList<>();
    }

    @Test
    void vesselLostWhileUnderwayAlerts() {
        VesselTrack track = TestTracks.activeTrackAt(257000001, 59.0, 10.5, 11.2, 0, t0);

        detector.onStateChange(track, TrackState.COASTING, TrackState.LOST, alerts::add);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().type()).isEqualTo(AlertType.AIS_GAP);
        assertThat(alerts.getFirst().severity()).isEqualTo(Alert.Severity.WARNING);
    }

    @Test
    void tankerGoingDarkIsCritical() {
        VesselTrack track = TestTracks.activeTrackAt(257000002, 59.0, 10.5, 11.2, 0, t0);
        TestTracks.setInfo(track, VesselInfo.UNKNOWN.mergeVoyage(
                "SHADOW TRADER", null, 84, 240, 40, 14.0, null, -1));

        detector.onStateChange(track, TrackState.COASTING, TrackState.LOST, alerts::add);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().severity()).isEqualTo(Alert.Severity.CRITICAL);
        assertThat(alerts.getFirst().vesselName()).isEqualTo("SHADOW TRADER");
    }

    @Test
    void mooredVesselFallingSilentIsRoutine() {
        VesselTrack track = TestTracks.activeTrackAt(257000003, 59.0, 10.5, 0.0, 5, t0);

        detector.onStateChange(track, TrackState.COASTING, TrackState.LOST, alerts::add);

        assertThat(alerts).isEmpty();
    }

    @Test
    void nonLostTransitionsAreIgnored() {
        VesselTrack track = TestTracks.activeTrackAt(257000004, 59.0, 10.5, 11.2, 0, t0);

        detector.onStateChange(track, TrackState.ACTIVE, TrackState.COASTING, alerts::add);

        assertThat(alerts).isEmpty();
    }
}
