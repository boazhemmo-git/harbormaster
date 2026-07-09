package io.harbormaster.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.harbormaster.config.SourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Replays a recorded AIS capture (NDJSON lines of {@code {"t": epochSeconds,
 * "raw": "!AIVDM..."}}) preserving original inter-arrival gaps, optionally
 * time-compressed. Loops forever by default so the demo never goes quiet.
 *
 * <p>This is the default source: the repository ships with a real ~15-minute
 * capture from the Norwegian Coastal Administration's open feed, so
 * {@code docker compose up} produces live-looking traffic with no network
 * dependency or API keys (ADR-0002).
 */
public final class ReplaySource implements AisSource {

    private static final Logger log = LoggerFactory.getLogger(ReplaySource.class);
    private static final long MAX_SLEEP_MS = 2000;

    private final SourceProperties.Replay config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public ReplaySource(SourceProperties.Replay config) {
        this.config = config;
    }

    @Override
    public void start(Consumer<TimestampedLine> sink) {
        running.set(true);
        worker = Thread.ofVirtual().name("replay-source").start(() -> run(sink));
    }

    private void run(Consumer<TimestampedLine> sink) {
        do {
            try {
                replayOnce(sink);
            } catch (IOException e) {
                log.error("Replay failed reading {}: {}", config.file(), e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (running.get() && config.loop());
    }

    private void replayOnce(Consumer<TimestampedLine> sink) throws IOException, InterruptedException {
        try (BufferedReader reader = open()) {
            double previousEpoch = -1;
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                double epoch = node.path("t").asDouble();
                String raw = node.path("raw").asText();
                if (raw.isEmpty()) {
                    continue;
                }
                if (previousEpoch >= 0 && epoch > previousEpoch) {
                    long gapMs = Math.round((epoch - previousEpoch) * 1000 / config.speed());
                    Thread.sleep(Math.min(gapMs, MAX_SLEEP_MS));
                }
                previousEpoch = epoch;
                sink.accept(new TimestampedLine(Instant.now(), raw));
            }
        }
    }

    private BufferedReader open() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(config.file());
        InputStream in = resource.getInputStream();
        if (config.file().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
    }

    @Override
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public String describe() {
        return "replay:" + config.file() + " @" + config.speed() + "x";
    }
}
