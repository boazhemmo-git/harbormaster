package io.harbormaster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ingest source selection.
 * <ul>
 *   <li>{@code SIMULATION} (default) — deterministic scripted North Sea
 *       scenario encoded as real AIVDM; zero configuration, all four
 *       detectors demonstrably fire within minutes.</li>
 *   <li>{@code REPLAY} — replays a recorded NDJSON capture with original
 *       pacing (a real capture from the Norwegian coast is bundled).</li>
 *   <li>{@code LIVE_TCP} — streams an open NMEA-over-TCP feed such as the
 *       Norwegian Coastal Administration's.</li>
 *   <li>{@code KAFKA} — consumes raw NMEA lines from a Kafka topic, the shape
 *       of a real deployment where edge receivers publish and the pipeline
 *       scales out as a consumer group. Set {@code produce-simulation=true} to
 *       also run the scripted generator as a producer, making the mode a
 *       self-contained demo with no external feed.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "harbormaster.source")
public record SourceProperties(Mode mode, Simulation simulation, Replay replay, LiveTcp liveTcp, Kafka kafka) {

    public enum Mode { SIMULATION, REPLAY, LIVE_TCP, KAFKA }

    public SourceProperties {
        if (mode == null) {
            mode = Mode.SIMULATION;
        }
        if (simulation == null) {
            simulation = new Simulation(0, 0, 0, 0);
        }
        if (replay == null) {
            replay = new Replay(null, 0, true);
        }
        if (liveTcp == null) {
            liveTcp = new LiveTcp(null, 0);
        }
        if (kafka == null) {
            kafka = new Kafka(null, null, null, false);
        }
    }

    /** North Sea approaches west of Rotterdam by default. */
    public record Simulation(double centerLat, double centerLon, int vesselCount, long seed) {
        public Simulation {
            if (centerLat == 0) {
                centerLat = 52.2;
            }
            if (centerLon == 0) {
                centerLon = 3.8;
            }
            if (vesselCount <= 0) {
                vesselCount = 120;
            }
            if (seed == 0) {
                seed = 20260709;
            }
        }
    }

    public record Replay(String file, double speed, boolean loop) {
        public Replay {
            if (file == null || file.isBlank()) {
                file = "classpath:data/ais-replay-sample.ndjson.gz";
            }
            if (speed <= 0) {
                speed = 1.0;
            }
        }
    }

    public record LiveTcp(String host, int port) {
        public LiveTcp {
            if (host == null || host.isBlank()) {
                host = "153.44.253.27"; // Norwegian Coastal Administration open AIS feed
            }
            if (port <= 0) {
                port = 5631;
            }
        }
    }

    /**
     * Kafka ingestion. The pipeline consumes raw NMEA lines (String values)
     * from {@code topic} as a member of {@code groupId} — add instances and
     * Kafka rebalances partitions across them, which is the whole point of the
     * mode. {@code produceSimulation} additionally runs the scripted generator
     * as a producer into the same topic, so {@code KAFKA} works as a
     * self-contained demo without a real upstream feed.
     */
    public record Kafka(String bootstrapServers, String topic, String groupId, boolean produceSimulation) {
        public Kafka {
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                bootstrapServers = "localhost:9092";
            }
            if (topic == null || topic.isBlank()) {
                topic = "ais.nmea";
            }
            if (groupId == null || groupId.isBlank()) {
                groupId = "harbormaster";
            }
        }
    }
}
