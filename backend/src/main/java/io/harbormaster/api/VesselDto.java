package io.harbormaster.api;

import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.VesselTrack;

import java.time.Instant;
import java.util.List;

/** Wire shape of a vessel for the REST snapshot and WebSocket deltas. */
public record VesselDto(
        int mmsi,
        String name,
        String callsign,
        int shipType,
        int lengthM,
        int beamM,
        String destination,
        double lat,
        double lon,
        Double sogKn,
        Double cogDeg,
        Integer heading,
        int navStatus,
        String state,
        Instant lastSeen,
        List<double[]> trail) {

    public static VesselDto from(VesselTrack track, boolean includeTrail) {
        Fix fix = track.latestFix();
        if (fix == null) {
            return null;
        }
        List<double[]> trail = includeTrail
                ? track.recentFixes().stream().map(f -> new double[]{f.lat(), f.lon()}).toList()
                : null;
        var info = track.info();
        return new VesselDto(
                track.mmsi(),
                info.name(),
                info.callsign(),
                info.shipType(),
                info.lengthM(),
                info.beamM(),
                info.destination(),
                fix.lat(),
                fix.lon(),
                Double.isNaN(fix.sogKn()) ? null : fix.sogKn(),
                Double.isNaN(fix.cogDeg()) ? null : fix.cogDeg(),
                fix.heading() < 0 ? null : fix.heading(),
                fix.navStatus(),
                track.state().name(),
                track.lastSeen(),
                trail);
    }
}
