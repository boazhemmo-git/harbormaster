package io.harbormaster.pipeline;

import io.harbormaster.ais.AisDecodeException;
import io.harbormaster.ais.AisDecoder;
import io.harbormaster.ais.PositionReport;
import io.harbormaster.ais.StaticDataReport;
import io.harbormaster.ais.StaticVoyageData;
import io.harbormaster.ais.UnsupportedMessage;
import io.harbormaster.config.SourceProperties;
import io.harbormaster.detection.DetectionEngine;
import io.harbormaster.ingest.AisSource;
import io.harbormaster.ingest.KafkaSource;
import io.harbormaster.ingest.LiveTcpSource;
import io.harbormaster.ingest.ReplaySource;
import io.harbormaster.ingest.SimulationSource;
import io.harbormaster.ingest.TimestampedLine;
import io.harbormaster.nmea.FragmentAssembler;
import io.harbormaster.nmea.NmeaParser;
import io.harbormaster.tracking.TrackStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Wires the pipeline: source → bounded queue → single decode worker →
 * track store → detection engine.
 *
 * <p>The queue is bounded with drop-oldest overflow: under a burst the
 * freshest data wins, staleness is bounded, and the drop counter makes the
 * loss visible instead of silent. One decode worker (a virtual thread) is
 * deliberate — it makes the store single-writer and the assembler safely
 * unsynchronized, and a single worker decodes far beyond real AIS feed rates
 * (see ADR-0001 for the measured headroom and the scale-out seam).
 */
@Service
public class PipelineService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);
    private static final int QUEUE_CAPACITY = 10_000;

    private final SourceProperties sourceProperties;
    private final TrackStore trackStore;
    private final DetectionEngine detectionEngine;
    private final PipelineStats stats;
    private final ObservationRegistry observationRegistry;

    private final BlockingQueue<TimestampedLine> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final FragmentAssembler assembler = new FragmentAssembler();

    private AisSource source;
    private Thread decodeWorker;
    private volatile boolean running;

    public PipelineService(SourceProperties sourceProperties, TrackStore trackStore,
                           DetectionEngine detectionEngine, PipelineStats stats,
                           ObservationRegistry observationRegistry) {
        this.sourceProperties = sourceProperties;
        this.trackStore = trackStore;
        this.detectionEngine = detectionEngine;
        this.stats = stats;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void start() {
        running = true;
        source = switch (sourceProperties.mode()) {
            case SIMULATION -> new SimulationSource(sourceProperties.simulation());
            case REPLAY -> new ReplaySource(sourceProperties.replay());
            case LIVE_TCP -> new LiveTcpSource(sourceProperties.liveTcp());
            case KAFKA -> new KafkaSource(sourceProperties.kafka(), sourceProperties.simulation());
        };
        decodeWorker = Thread.ofVirtual().name("decode-worker").start(this::decodeLoop);
        source.start(this::enqueue);
        log.info("Pipeline started with source {}", source.describe());
    }

    private void enqueue(TimestampedLine line) {
        stats.linesIn.increment();
        while (!queue.offer(line)) {
            queue.poll();
            stats.linesDropped.increment();
        }
    }

    private void decodeLoop() {
        while (running) {
            TimestampedLine line;
            try {
                line = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // One observation per message: the OTel bridge turns it into a span
            // (when tracing is enabled) and Micrometer turns it into the
            // `ais.process` timer histogram (always), tagged by outcome so a
            // Grafana panel can break latency and volume down by result.
            Observation observation = Observation.start("ais.process", observationRegistry);
            try (Observation.Scope ignored = observation.openScope()) {
                observation.lowCardinalityKeyValue("outcome", process(line, observation).tag);
            } catch (RuntimeException e) {
                // Never let one hostile line kill the pipeline.
                observation.error(e);
                observation.lowCardinalityKeyValue("outcome", Outcome.ERROR.tag);
                stats.decodeErrors.increment();
                log.debug("Failed to process line: {}", line.raw(), e);
            } finally {
                observation.stop();
            }
            stats.recordLatencyMicros(java.time.Duration.between(line.receivedAt(), Instant.now()).toNanos() / 1000);
        }
    }

    private Outcome process(TimestampedLine line, Observation observation) {
        var sentence = NmeaParser.parse(line.raw());
        if (sentence.isEmpty()) {
            stats.checksumOrFormatRejected.increment();
            return Outcome.REJECTED;
        }
        stats.sentencesParsed.increment();

        var assembled = assembler.offer(sentence.get(), line.receivedAt());
        if (assembled.isEmpty()) {
            return Outcome.INCOMPLETE; // waiting for more fragments
        }

        try {
            var message = AisDecoder.decode(assembled.get().payload(), assembled.get().fillBits());
            stats.markDecoded(line.receivedAt().getEpochSecond());
            // Message type is bounded (low cardinality → metric tag); MMSI is
            // per-vessel (high cardinality → span attribute only, never a tag).
            observation.lowCardinalityKeyValue("message.type", String.valueOf(message.type()));
            observation.highCardinalityKeyValue("mmsi", String.valueOf(message.mmsi()));

            switch (message) {
                case PositionReport report -> {
                    var update = trackStore.applyPosition(report, line.receivedAt());
                    if (update != null) {
                        stats.positionsApplied.increment();
                        detectionEngine.onFix(update.track(), update.previous(), update.current());
                    }
                }
                case StaticVoyageData data -> trackStore.applyStatic(data);
                case StaticDataReport report -> trackStore.applyStatic(report);
                case UnsupportedMessage ignored -> stats.unsupportedMessages.increment();
            }
            return Outcome.DECODED;
        } catch (AisDecodeException e) {
            stats.decodeErrors.increment();
            return Outcome.DECODE_ERROR;
        }
    }

    /** Terminal result of processing one line — the {@code outcome} span/metric tag. */
    private enum Outcome {
        REJECTED("rejected"),
        INCOMPLETE("incomplete"),
        DECODED("decoded"),
        DECODE_ERROR("decode_error"),
        ERROR("error");

        final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    /** Ages tracks and runs the cross-vessel detectors. */
    @Scheduled(fixedDelayString = "PT5S")
    public void sweep() {
        if (!running) {
            return;
        }
        Instant now = Instant.now();
        for (var change : trackStore.sweep(now)) {
            detectionEngine.onStateChange(change.track(), change.from(), change.to());
        }
        detectionEngine.onSweep(trackStore.all(), now);
    }

    public String sourceDescription() {
        return source != null ? source.describe() : "not started";
    }

    public SourceProperties.Mode mode() {
        return sourceProperties.mode();
    }

    @Override
    public void stop() {
        running = false;
        if (source != null) {
            source.stop();
        }
        if (decodeWorker != null) {
            decodeWorker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
