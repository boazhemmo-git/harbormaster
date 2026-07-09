package io.harbormaster.tracking;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable per-vessel state: identity, recent fix history and lifecycle state.
 *
 * <p>All mutation happens on the single pipeline worker thread; reads from
 * API threads see a consistent-enough snapshot via {@code synchronized}
 * accessors around the fix deque (the only structurally mutated collection).
 */
public final class VesselTrack {

    private final int mmsi;
    private final int trailLength;
    private final Deque<Fix> fixes = new ArrayDeque<>();
    private final Map<String, Instant> detectorCooldowns = new ConcurrentHashMap<>();

    private volatile VesselInfo info = VesselInfo.UNKNOWN;
    private volatile TrackState state = TrackState.ACQUIRING;
    private volatile Instant lastSeen;
    private volatile boolean dirty; // has updates not yet flushed to websocket clients

    VesselTrack(int mmsi, int trailLength) {
        this.mmsi = mmsi;
        this.trailLength = trailLength;
    }

    public int mmsi() {
        return mmsi;
    }

    public VesselInfo info() {
        return info;
    }

    void updateInfo(VesselInfo newInfo) {
        this.info = newInfo;
        this.dirty = true;
    }

    public TrackState state() {
        return state;
    }

    void setState(TrackState newState) {
        this.state = newState;
        this.dirty = true;
    }

    public Instant lastSeen() {
        return lastSeen;
    }

    public boolean consumeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    synchronized void addFix(Fix fix) {
        fixes.addLast(fix);
        if (fixes.size() > trailLength) {
            fixes.removeFirst();
        }
        lastSeen = fix.time();
        dirty = true;
    }

    public synchronized Fix latestFix() {
        return fixes.peekLast();
    }

    public synchronized Fix previousFix() {
        if (fixes.size() < 2) {
            return null;
        }
        var it = fixes.descendingIterator();
        it.next();
        return it.next();
    }

    public synchronized List<Fix> recentFixes() {
        return new ArrayList<>(fixes);
    }

    public synchronized int fixCount() {
        return fixes.size();
    }

    /**
     * Per-detector alert cooldown so one misbehaving vessel does not flood
     * the alert feed.
     *
     * @return true if the cooldown had expired and was re-armed
     */
    public boolean tryArmCooldown(String detectorKey, Instant now, long cooldownSeconds) {
        Instant last = detectorCooldowns.get(detectorKey);
        if (last != null && now.isBefore(last.plusSeconds(cooldownSeconds))) {
            return false;
        }
        detectorCooldowns.put(detectorKey, now);
        return true;
    }

    public String bestName() {
        String name = info.name();
        return name != null && !name.isBlank() ? name : "MMSI " + mmsi;
    }
}
