package io.harbormaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Track lifecycle timing. Defaults suit the bundled replay demo; live
 * shore-station coverage warrants longer windows (see application.yml
 * comments).
 */
@ConfigurationProperties(prefix = "harbormaster.tracking")
public record TrackingProperties(
        Duration coastAfter,
        Duration lostAfter,
        Duration evictAfter,
        int trailLength) {

    public TrackingProperties {
        if (coastAfter == null) {
            coastAfter = Duration.ofSeconds(90);
        }
        if (lostAfter == null) {
            lostAfter = Duration.ofSeconds(300);
        }
        if (evictAfter == null) {
            evictAfter = Duration.ofSeconds(1800);
        }
        if (trailLength <= 0) {
            trailLength = 50;
        }
    }
}
