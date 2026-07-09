package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.TestTracks;
import io.harbormaster.tracking.VesselInfo;
import io.harbormaster.tracking.VesselTrack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendezvousDetectorTest {

    private final Instant t0 = Instant.parse("2026-06-01T12:00:00Z");
    private RendezvousDetector detector;
    private List<Alert> alerts;

    @BeforeEach
    void setUp() {
        detector = new RendezvousDetector(new DetectionProperties(
                null, null, null,
                new DetectionProperties.Rendezvous(300, Duration.ofMinutes(3), Duration.ofMinutes(30))));
        alerts = new ArrayList<>();
    }

    @Test
    void sustainedCloseStationKeepingAlerts() {
        // Two vessels ~150 m apart in open water, one still claiming "under way"
        VesselTrack tanker = TestTracks.activeTrackAt(511000001, 58.500, 10.500, 0.4, 0, t0);
        VesselTrack feeder = TestTracks.activeTrackAt(511000002, 58.5013, 10.500, 0.6, 0, t0);
        TestTracks.setInfo(tanker, VesselInfo.UNKNOWN.mergeVoyage("GHOST TANKER", null, 81, -1, -1,
                Double.NaN, null, -1));
        List<VesselTrack> tracks = List.of(tanker, feeder);

        detector.onSweep(tracks, t0, alerts::add);                     // pair first seen
        detector.onSweep(tracks, t0.plusSeconds(60), alerts::add);     // 1 min — too early
        assertThat(alerts).isEmpty();

        detector.onSweep(tracks, t0.plusSeconds(200), alerts::add);    // 3m20s — sustained
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().type()).isEqualTo(AlertType.RENDEZVOUS);
        assertThat(alerts.getFirst().severity()).isEqualTo(Alert.Severity.CRITICAL); // tanker involved
    }

    @Test
    void separationResetsThePairClock() {
        VesselTrack a = TestTracks.activeTrackAt(511000003, 58.500, 10.500, 0.4, 0, t0);
        VesselTrack b = TestTracks.activeTrackAt(511000004, 58.5013, 10.500, 0.6, 0, t0);

        detector.onSweep(List.of(a, b), t0, alerts::add);

        // They separate: vessel b moves ~2 km away, then returns
        VesselTrack bFar = TestTracks.activeTrackAt(511000004, 58.520, 10.500, 0.6, 0, t0.plusSeconds(60));
        detector.onSweep(List.of(a, bFar), t0.plusSeconds(60), alerts::add);

        VesselTrack bBack = TestTracks.activeTrackAt(511000004, 58.5013, 10.500, 0.6, 0, t0.plusSeconds(120));
        detector.onSweep(List.of(a, bBack), t0.plusSeconds(120), alerts::add);
        // Only 100 s since re-approach — the earlier proximity must not count
        detector.onSweep(List.of(a, bBack), t0.plusSeconds(220), alerts::add);

        assertThat(alerts).isEmpty();
    }

    @Test
    void tugsAlongsideAreNotSuspicious() {
        VesselTrack ship = TestTracks.activeTrackAt(511000005, 58.500, 10.500, 0.4, 0, t0);
        VesselTrack tug = TestTracks.activeTrackAt(511000006, 58.5005, 10.500, 0.8, 0, t0);
        TestTracks.setInfo(tug, VesselInfo.UNKNOWN.mergeCallsignAndType("TUG1", 52));
        List<VesselTrack> tracks = List.of(ship, tug);

        detector.onSweep(tracks, t0, alerts::add);
        detector.onSweep(tracks, t0.plusSeconds(300), alerts::add);

        assertThat(alerts).isEmpty();
    }

    @Test
    void bothMooredTogetherIsHarborLifeNotRendezvous() {
        VesselTrack a = TestTracks.activeTrackAt(511000007, 58.500, 10.500, 0.0, 5, t0);
        VesselTrack b = TestTracks.activeTrackAt(511000008, 58.5005, 10.500, 0.0, 5, t0);
        List<VesselTrack> tracks = List.of(a, b);

        detector.onSweep(tracks, t0, alerts::add);
        detector.onSweep(tracks, t0.plusSeconds(300), alerts::add);

        assertThat(alerts).isEmpty();
    }
}
