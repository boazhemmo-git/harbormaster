package io.harbormaster.pipeline;

import io.harbormaster.detection.AlertLog;
import io.harbormaster.detection.AlertType;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.TrackStore;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Bridges the lock-free {@link PipelineStats} counters onto Micrometer, so the
 * same numbers the live dashboard shows are also exported at
 * {@code /actuator/prometheus} for Grafana. The {@link PipelineStats} adders
 * stay the source of truth — these meters are thin read-only views over them
 * ({@link FunctionCounter}/{@link Gauge} both take a supplier), so nothing is
 * double-counted and the hot path is untouched.
 *
 * <p>Registered once at startup; the meters read live values on each scrape.
 */
@Component
public class PipelineMetrics {

    public PipelineMetrics(MeterRegistry registry, PipelineStats stats,
                           TrackStore trackStore, AlertLog alertLog) {

        counter(registry, "harbormaster.lines.received", stats, s -> s.linesIn.sum(),
                "Raw NMEA lines accepted from the source");
        counter(registry, "harbormaster.lines.dropped", stats, s -> s.linesDropped.sum(),
                "Lines dropped by the bounded queue under backpressure");
        counter(registry, "harbormaster.sentences.parsed", stats, s -> s.sentencesParsed.sum(),
                "NMEA sentences that passed checksum and format validation");
        counter(registry, "harbormaster.sentences.rejected", stats, s -> s.checksumOrFormatRejected.sum(),
                "Sentences rejected on checksum or format");
        counter(registry, "harbormaster.messages.decoded", stats, s -> s.messagesDecoded.sum(),
                "AIS messages fully six-bit decoded");
        counter(registry, "harbormaster.messages.unsupported", stats, s -> s.unsupportedMessages.sum(),
                "Decoded messages of a type the pipeline does not model");
        counter(registry, "harbormaster.decode.errors", stats, s -> s.decodeErrors.sum(),
                "Payloads that failed six-bit decoding");
        counter(registry, "harbormaster.positions.applied", stats, s -> s.positionsApplied.sum(),
                "Position reports applied to a vessel track");

        Gauge.builder("harbormaster.messages.rate", stats,
                        s -> s.messagesPerSecond(Instant.now().getEpochSecond()))
                .description("Decoded messages per second (trailing minute)")
                .baseUnit("messages/second")
                .register(registry);

        Gauge.builder("harbormaster.decode.latency.p50", stats,
                        s -> s.latencyPercentiles().p50Micros() / 1_000_000.0)
                .description("Median receive→applied latency")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("harbormaster.decode.latency.p99", stats,
                        s -> s.latencyPercentiles().p99Micros() / 1_000_000.0)
                .description("p99 receive→applied latency")
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("harbormaster.vessels", trackStore, TrackStore::size)
                .description("Vessels currently tracked")
                .register(registry);

        // One gauge per lifecycle state, tagged — Grafana can stack them.
        for (TrackState state : TrackState.values()) {
            Gauge.builder("harbormaster.vessels.by_state", trackStore, ts -> countByState(ts, state))
                    .tag("state", state.name())
                    .description("Vessels in a given tracking-lifecycle state")
                    .register(registry);
        }

        // Cumulative alerts per detector type.
        for (AlertType type : AlertType.values()) {
            Gauge.builder("harbormaster.alerts", alertLog, log -> log.totalsByType().getOrDefault(type, 0))
                    .tag("type", type.name())
                    .description("Alerts raised, by detector")
                    .register(registry);
        }
    }

    private static void counter(MeterRegistry registry, String name, PipelineStats stats,
                                java.util.function.ToDoubleFunction<PipelineStats> value, String description) {
        FunctionCounter.builder(name, stats, value)
                .description(description)
                .register(registry);
    }

    private static double countByState(TrackStore trackStore, TrackState state) {
        long count = 0;
        for (var track : trackStore.all()) {
            if (track.state() == state) {
                count++;
            }
        }
        return count;
    }
}
