package io.harbormaster.tracking;

/**
 * Track lifecycle. A track is born {@code ACQUIRING} on its first fix,
 * promoted to {@code ACTIVE} once a second fix corroborates it, degrades to
 * {@code COASTING} when updates stop arriving (position increasingly stale),
 * and to {@code LOST} when the silence exceeds the lost threshold — the
 * transition the dark-ship detector listens for. Lost tracks that resume
 * transmitting return to {@code ACTIVE}.
 */
public enum TrackState {
    ACQUIRING,
    ACTIVE,
    COASTING,
    LOST
}
