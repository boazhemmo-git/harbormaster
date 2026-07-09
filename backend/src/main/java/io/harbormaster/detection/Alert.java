package io.harbormaster.detection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An anomaly raised by a detector, immutable and ready for serialization. */
public record Alert(
        String id,
        Instant time,
        AlertType type,
        Severity severity,
        int mmsi,
        String vesselName,
        double lat,
        double lon,
        String message,
        Map<String, Object> details) {

    public enum Severity { INFO, WARNING, CRITICAL }

    public static Alert of(Instant time, AlertType type, Severity severity, int mmsi, String vesselName,
                           double lat, double lon, String message, Map<String, Object> details) {
        return new Alert(UUID.randomUUID().toString(), time, type, severity, mmsi, vesselName,
                lat, lon, message, details);
    }
}
