package io.harbormaster.detection;

import io.harbormaster.config.DetectionProperties;
import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Detects possible ship-to-ship transfers: two distinct vessels holding
 * station within touching distance of each other, sustained over minutes.
 * This is the canonical mid-sea cargo/fuel transfer pattern used to launder
 * the origin of sanctioned goods.
 *
 * <p>Candidate pairs are found with a spatial grid (~550 m cells, 3×3
 * neighborhood scan) rather than an O(n²) pass over all vessels, so the
 * sweep stays cheap at thousands of tracks. Pairs involving port-service
 * vessels (tugs, pilots) are ignored — coming alongside is their job.
 */
@Component
public class RendezvousDetector implements AnomalyDetector {

    private static final double CELL_DEGREES = 0.005;
    private static final double MAX_PAIR_SOG_KN = 2.0;

    private final DetectionProperties.Rendezvous config;

    /** First time each nearby pair was observed together, keyed by MMSI pair. */
    private final Map<Long, Instant> pairFirstSeen = new HashMap<>();
    private final Map<Long, Instant> pairAlerted = new HashMap<>();

    public RendezvousDetector(DetectionProperties properties) {
        this.config = properties.rendezvous();
    }

    @Override
    public void onSweep(Collection<VesselTrack> tracks, Instant now, Consumer<Alert> alerts) {
        Map<Long, List<VesselTrack>> grid = buildGridOfSlowVessels(tracks);
        Map<Long, Instant> stillTogether = new HashMap<>();

        for (List<VesselTrack> cell : grid.values()) {
            for (VesselTrack a : cell) {
                for (VesselTrack b : neighborhood(grid, a)) {
                    if (b.mmsi() <= a.mmsi()) {
                        continue; // each unordered pair once
                    }
                    evaluatePair(a, b, now, stillTogether, alerts);
                }
            }
        }
        // Pairs that separated reset their clock.
        pairFirstSeen.keySet().retainAll(stillTogether.keySet());
        pairFirstSeen.putAll(stillTogether);
        expireOldAlertMarks(now);
    }

    private void evaluatePair(VesselTrack a, VesselTrack b, Instant now,
                              Map<Long, Instant> stillTogether, Consumer<Alert> alerts) {
        Fix fa = a.latestFix();
        Fix fb = b.latestFix();
        double distance = Geo.distanceM(fa.lat(), fa.lon(), fb.lat(), fb.lon());
        if (distance > config.maxDistanceM()) {
            return;
        }
        long key = pairKey(a.mmsi(), b.mmsi());
        Instant firstSeen = pairFirstSeen.getOrDefault(key, now);
        stillTogether.put(key, firstSeen);

        if (Duration.between(firstSeen, now).compareTo(config.minDuration()) < 0) {
            return;
        }
        // Both merely anchored/moored together is harbor life, not a rendezvous.
        if (fa.isStationaryStatus() && fb.isStationaryStatus()) {
            return;
        }
        Instant lastAlert = pairAlerted.get(key);
        if (lastAlert != null && Duration.between(lastAlert, now).compareTo(config.pairCooldown()) < 0) {
            return;
        }
        pairAlerted.put(key, now);

        boolean highInterest = a.info().isHighInterestType() || b.info().isHighInterestType();
        alerts.accept(Alert.of(
                now, AlertType.RENDEZVOUS,
                highInterest ? Alert.Severity.CRITICAL : Alert.Severity.WARNING,
                a.mmsi(), a.bestName(),
                midpoint(fa.lat(), fb.lat()), midpoint(fa.lon(), fb.lon()),
                "%s and %s holding station %d m apart for %d+ minutes — possible ship-to-ship transfer".formatted(
                        a.bestName(), b.bestName(), Math.round(distance), config.minDuration().toMinutes()),
                Map.of(
                        "otherMmsi", b.mmsi(),
                        "otherName", b.bestName(),
                        "distanceM", Math.round(distance),
                        "sinceMinutes", Duration.between(firstSeen, now).toMinutes())));
    }

    private Map<Long, List<VesselTrack>> buildGridOfSlowVessels(Collection<VesselTrack> tracks) {
        Map<Long, List<VesselTrack>> grid = new HashMap<>();
        for (VesselTrack track : tracks) {
            if (track.state() != TrackState.ACTIVE || track.info().isPortServiceType()) {
                continue;
            }
            Fix fix = track.latestFix();
            if (fix == null || Double.isNaN(fix.sogKn()) || fix.sogKn() > MAX_PAIR_SOG_KN) {
                continue;
            }
            grid.computeIfAbsent(cellKey(fix.lat(), fix.lon()), k -> new ArrayList<>()).add(track);
        }
        return grid;
    }

    private List<VesselTrack> neighborhood(Map<Long, List<VesselTrack>> grid, VesselTrack center) {
        Fix fix = center.latestFix();
        int row = (int) Math.floor(fix.lat() / CELL_DEGREES);
        int col = (int) Math.floor(fix.lon() / CELL_DEGREES);
        List<VesselTrack> result = new ArrayList<>();
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                List<VesselTrack> cell = grid.get(pack(row + dr, col + dc));
                if (cell != null) {
                    result.addAll(cell);
                }
            }
        }
        return result;
    }

    private void expireOldAlertMarks(Instant now) {
        Iterator<Map.Entry<Long, Instant>> it = pairAlerted.entrySet().iterator();
        while (it.hasNext()) {
            if (Duration.between(it.next().getValue(), now).compareTo(config.pairCooldown().multipliedBy(4)) > 0) {
                it.remove();
            }
        }
    }

    private static long cellKey(double lat, double lon) {
        return pack((int) Math.floor(lat / CELL_DEGREES), (int) Math.floor(lon / CELL_DEGREES));
    }

    private static long pack(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }

    private static long pairKey(int mmsiA, int mmsiB) {
        int lo = Math.min(mmsiA, mmsiB);
        int hi = Math.max(mmsiA, mmsiB);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static double midpoint(double a, double b) {
        return (a + b) / 2.0;
    }
}
