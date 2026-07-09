package io.harbormaster.ingest;

import java.util.function.Consumer;

/**
 * A producer of raw NMEA lines. Implementations own their thread (virtual)
 * and must keep producing until {@link #stop()} — transient failures are
 * theirs to retry, not the pipeline's.
 */
public interface AisSource {

    void start(Consumer<TimestampedLine> sink);

    void stop();

    /** Human-readable description for the stats endpoint. */
    String describe();
}
