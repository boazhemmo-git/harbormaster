package io.harbormaster.tracking;

import io.harbormaster.ais.PositionReport;
import io.harbormaster.ais.StaticDataReport;
import io.harbormaster.ais.StaticVoyageData;
import io.harbormaster.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of live vessel tracks keyed by MMSI, with a periodic sweep
 * that ages tracks through {@link TrackState} and eventually evicts them.
 * See ADR-0003 for why this is a bounded in-memory structure and not a
 * database.
 */
@Component
public class TrackStore {

    /** A position update: the track plus the fix that preceded it (nullable). */
    public record PositionUpdate(VesselTrack track, Fix previous, Fix current) {
    }

    /** A lifecycle transition produced by the sweep. */
    public record StateChange(VesselTrack track, TrackState from, TrackState to) {
    }

    private final Map<Integer, VesselTrack> tracks = new ConcurrentHashMap<>();
    private final TrackingProperties config;

    public TrackStore(TrackingProperties config) {
        this.config = config;
    }

    /**
     * Applies a decoded position report.
     *
     * @return the update, or null when the report carried no usable position
     */
    public PositionUpdate applyPosition(PositionReport report, Instant receivedAt) {
        if (!report.hasPosition() || !validMmsi(report.mmsi())) {
            return null;
        }
        VesselTrack track = tracks.computeIfAbsent(
                report.mmsi(), mmsi -> new VesselTrack(mmsi, config.trailLength()));

        Fix previous = track.latestFix();
        Fix current = new Fix(
                receivedAt,
                report.latitude(),
                report.longitude(),
                report.speedOverGroundKn(),
                report.courseOverGroundDeg(),
                report.heading(),
                report.navStatus());
        track.addFix(current);

        if (report.name() != null) {
            track.updateInfo(track.info().mergeName(report.name()));
        }
        if (track.state() == TrackState.ACQUIRING && track.fixCount() >= 2) {
            track.setState(TrackState.ACTIVE);
        } else if (track.state() == TrackState.COASTING || track.state() == TrackState.LOST) {
            track.setState(TrackState.ACTIVE);
        }
        return new PositionUpdate(track, previous, current);
    }

    public void applyStatic(StaticVoyageData data) {
        if (!validMmsi(data.mmsi())) {
            return;
        }
        VesselTrack track = tracks.computeIfAbsent(
                data.mmsi(), mmsi -> new VesselTrack(mmsi, config.trailLength()));
        int length = data.dimToBowM() > 0 || data.dimToSternM() > 0 ? data.dimToBowM() + data.dimToSternM() : -1;
        int beam = data.dimToPortM() > 0 || data.dimToStarboardM() > 0 ? data.dimToPortM() + data.dimToStarboardM() : -1;
        track.updateInfo(track.info().mergeVoyage(
                data.name(), data.callsign(), data.shipType(),
                length, beam, data.draughtM(), data.destination(), data.imoNumber()));
    }

    public void applyStatic(StaticDataReport report) {
        if (!validMmsi(report.mmsi())) {
            return;
        }
        VesselTrack track = tracks.computeIfAbsent(
                report.mmsi(), mmsi -> new VesselTrack(mmsi, config.trailLength()));
        if (report.partNumber() == 0) {
            track.updateInfo(track.info().mergeName(report.name()));
        } else {
            track.updateInfo(track.info().mergeCallsignAndType(report.callsign(), report.shipType()));
        }
    }

    /**
     * Ages every track: ACTIVE fades to COASTING, COASTING to LOST, and LOST
     * tracks are evicted after the retention window. Returns the transitions
     * so the detection engine can react (dark-ship detection keys off
     * {@code -> LOST}).
     */
    public List<StateChange> sweep(Instant now) {
        List<StateChange> changes = new ArrayList<>();
        var iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            VesselTrack track = iterator.next().getValue();
            Instant lastSeen = track.lastSeen();
            if (lastSeen == null) {
                continue;
            }
            Duration silence = Duration.between(lastSeen, now);
            TrackState state = track.state();

            if (silence.compareTo(config.evictAfter()) > 0) {
                iterator.remove();
            } else if (state == TrackState.COASTING && silence.compareTo(config.lostAfter()) > 0) {
                track.setState(TrackState.LOST);
                changes.add(new StateChange(track, TrackState.COASTING, TrackState.LOST));
            } else if ((state == TrackState.ACTIVE || state == TrackState.ACQUIRING)
                    && silence.compareTo(config.coastAfter()) > 0) {
                track.setState(TrackState.COASTING);
                changes.add(new StateChange(track, state, TrackState.COASTING));
            }
        }
        return changes;
    }

    public Collection<VesselTrack> all() {
        return tracks.values();
    }

    public VesselTrack get(int mmsi) {
        return tracks.get(mmsi);
    }

    public int size() {
        return tracks.size();
    }

    /**
     * Valid ship MMSIs are 9 digits and not in the reserved coast-station
     * (00xxxxxxx) or SAR-aircraft ranges; group/auxiliary prefixes are kept
     * since they still describe real transmitters worth plotting.
     */
    private static boolean validMmsi(int mmsi) {
        return mmsi >= 100_000_000 && mmsi <= 999_999_999;
    }
}
