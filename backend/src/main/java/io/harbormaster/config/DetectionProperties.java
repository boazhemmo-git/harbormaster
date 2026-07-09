package io.harbormaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Thresholds for the anomaly detectors. */
@ConfigurationProperties(prefix = "harbormaster.detection")
public record DetectionProperties(
        Kinematic kinematic,
        DarkShip darkShip,
        Loitering loitering,
        Rendezvous rendezvous) {

    public DetectionProperties {
        if (kinematic == null) {
            kinematic = new Kinematic(0, null);
        }
        if (darkShip == null) {
            darkShip = new DarkShip(0);
        }
        if (loitering == null) {
            loitering = new Loitering(null, 0, null);
        }
        if (rendezvous == null) {
            rendezvous = new Rendezvous(0, null, null);
        }
    }

    /**
     * @param maxSpeedKnots implied speeds above this are physically
     *                      implausible for merchant traffic
     */
    public record Kinematic(double maxSpeedKnots, Duration cooldown) {
        public Kinematic {
            if (maxSpeedKnots <= 0) {
                maxSpeedKnots = 60;
            }
            if (cooldown == null) {
                cooldown = Duration.ofMinutes(5);
            }
        }
    }

    /** @param minSogKnots only vessels underway at least this fast can "go dark" */
    public record DarkShip(double minSogKnots) {
        public DarkShip {
            if (minSogKnots <= 0) {
                minSogKnots = 3.0;
            }
        }
    }

    public record Loitering(Duration window, double maxSogKnots, Duration cooldown) {
        public Loitering {
            if (window == null) {
                window = Duration.ofMinutes(10);
            }
            if (maxSogKnots <= 0) {
                maxSogKnots = 1.0;
            }
            if (cooldown == null) {
                cooldown = Duration.ofMinutes(15);
            }
        }
    }

    public record Rendezvous(double maxDistanceM, Duration minDuration, Duration pairCooldown) {
        public Rendezvous {
            if (maxDistanceM <= 0) {
                maxDistanceM = 300;
            }
            if (minDuration == null) {
                minDuration = Duration.ofMinutes(3);
            }
            if (pairCooldown == null) {
                pairCooldown = Duration.ofMinutes(30);
            }
        }
    }
}
