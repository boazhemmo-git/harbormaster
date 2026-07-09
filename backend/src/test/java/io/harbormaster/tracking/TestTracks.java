package io.harbormaster.tracking;

import java.time.Instant;

/** Test factory for tracks, bridging the package-private constructor. */
public final class TestTracks {

    private TestTracks() {
    }

    public static VesselTrack track(int mmsi) {
        return new VesselTrack(mmsi, 50);
    }

    public static VesselTrack activeTrackAt(int mmsi, double lat, double lon, double sogKn,
                                            int navStatus, Instant time) {
        VesselTrack track = track(mmsi);
        track.addFix(new Fix(time.minusSeconds(30), lat, lon, sogKn, 0.0, 0, navStatus));
        track.addFix(new Fix(time, lat, lon, sogKn, 0.0, 0, navStatus));
        track.setState(TrackState.ACTIVE);
        return track;
    }

    public static void addFix(VesselTrack track, Fix fix) {
        track.addFix(fix);
    }

    public static void setState(VesselTrack track, TrackState state) {
        track.setState(state);
    }

    public static void setInfo(VesselTrack track, VesselInfo info) {
        track.updateInfo(info);
    }
}
