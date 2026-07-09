package io.harbormaster.detection;

public enum AlertType {
    /** Vessel stopped transmitting while underway — possible transponder shutdown. */
    AIS_GAP,
    /** Physically implausible movement between fixes — possible position spoofing. */
    KINEMATIC_ANOMALY,
    /** Vessel reports "under way" but has been effectively stationary. */
    LOITERING,
    /** Two vessels stationary alongside each other at sea — possible ship-to-ship transfer. */
    RENDEZVOUS
}
