package io.harbormaster.ingest;

import java.time.Instant;

/** A raw NMEA line and the moment it entered the pipeline. */
public record TimestampedLine(Instant receivedAt, String raw) {
}
