package io.harbormaster.tracking;

import io.harbormaster.ais.PositionReport;
import io.harbormaster.ais.StaticDataReport;
import io.harbormaster.ais.StaticVoyageData;
import io.harbormaster.config.TrackingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrackStoreTest {

    private final Instant t0 = Instant.parse("2026-06-01T12:00:00Z");
    private TrackStore store;

    @BeforeEach
    void setUp() {
        store = new TrackStore(new TrackingProperties(
                Duration.ofSeconds(90), Duration.ofSeconds(300), Duration.ofSeconds(1800), 50));
    }

    @Test
    void trackIsAcquiringOnFirstFixActiveOnSecond() {
        store.applyPosition(position(230000001, 59.0, 10.0), t0);
        assertThat(store.get(230000001).state()).isEqualTo(TrackState.ACQUIRING);

        store.applyPosition(position(230000001, 59.001, 10.0), t0.plusSeconds(10));
        assertThat(store.get(230000001).state()).isEqualTo(TrackState.ACTIVE);
    }

    @Test
    void silenceAgesTrackThroughCoastingToLostThenEvicts() {
        store.applyPosition(position(230000002, 59.0, 10.0), t0);
        store.applyPosition(position(230000002, 59.001, 10.0), t0.plusSeconds(10));

        var toCoasting = store.sweep(t0.plusSeconds(120));
        assertThat(toCoasting).singleElement()
                .satisfies(change -> assertThat(change.to()).isEqualTo(TrackState.COASTING));

        var toLost = store.sweep(t0.plusSeconds(400));
        assertThat(toLost).singleElement()
                .satisfies(change -> assertThat(change.to()).isEqualTo(TrackState.LOST));

        store.sweep(t0.plusSeconds(2000));
        assertThat(store.get(230000002)).isNull();
    }

    @Test
    void reappearingLostTrackReturnsToActive() {
        store.applyPosition(position(230000003, 59.0, 10.0), t0);
        store.applyPosition(position(230000003, 59.001, 10.0), t0.plusSeconds(10));
        store.sweep(t0.plusSeconds(120));
        store.sweep(t0.plusSeconds(400));
        assertThat(store.get(230000003).state()).isEqualTo(TrackState.LOST);

        store.applyPosition(position(230000003, 59.01, 10.0), t0.plusSeconds(500));

        assertThat(store.get(230000003).state()).isEqualTo(TrackState.ACTIVE);
    }

    @Test
    void classBIdentityMergesAcrossPartAAndPartB() {
        store.applyPosition(position(230000004, 59.0, 10.0), t0);
        store.applyStatic(new StaticDataReport(230000004, 0, "FRAM", null, -1));
        store.applyStatic(new StaticDataReport(230000004, 1, null, "LM1234", 36));

        VesselInfo info = store.get(230000004).info();
        assertThat(info.name()).isEqualTo("FRAM");
        assertThat(info.callsign()).isEqualTo("LM1234");
        assertThat(info.shipType()).isEqualTo(36);
    }

    @Test
    void voyageDataPopulatesDimensionsAndDestination() {
        store.applyStatic(new StaticVoyageData(230000005, 9111111, "LAJX", "TEST VESSEL",
                80, 200, 40, 15, 17, 12.5, "ROTTERDAM"));

        VesselInfo info = store.get(230000005).info();
        assertThat(info.lengthM()).isEqualTo(240);
        assertThat(info.beamM()).isEqualTo(32);
        assertThat(info.destination()).isEqualTo("ROTTERDAM");
        assertThat(info.isHighInterestType()).isTrue();
    }

    @Test
    void reportsWithoutPositionAreIgnored() {
        var report = new PositionReport(1, 230000006, 0, 5.0, Double.NaN, Double.NaN,
                90.0, 90, 10, null);

        assertThat(store.applyPosition(report, t0)).isNull();
        assertThat(store.get(230000006)).isNull();
    }

    @Test
    void invalidMmsiRangesAreRejected() {
        assertThat(store.applyPosition(position(1234, 59.0, 10.0), t0)).isNull();
        assertThat(store.size()).isZero();
    }

    private static PositionReport position(int mmsi, double lat, double lon) {
        return new PositionReport(1, mmsi, 0, 8.5, lon, lat, 45.0, 44, 10, null);
    }
}
