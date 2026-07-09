package io.harbormaster.detection;

import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;

import java.time.Instant;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * A detection strategy. Detectors are stateless where possible (per-vessel
 * bookkeeping lives on the track or inside the detector) and react to three
 * pipeline events; each hook is optional.
 */
public interface AnomalyDetector {

    /** A vessel produced a new accepted position fix. */
    default void onFix(VesselTrack track, Fix previous, Fix current, Consumer<Alert> alerts) {
    }

    /** The sweep changed a track's lifecycle state. */
    default void onStateChange(VesselTrack track, TrackState from, TrackState to, Consumer<Alert> alerts) {
    }

    /** Periodic pass over all live tracks, for cross-vessel and windowed rules. */
    default void onSweep(Collection<VesselTrack> tracks, Instant now, Consumer<Alert> alerts) {
    }
}
