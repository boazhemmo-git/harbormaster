package io.harbormaster.ingest;

import io.harbormaster.config.SourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streams raw NMEA from an open AIS-over-TCP feed (default: the Norwegian
 * Coastal Administration's public national feed, NLOD-licensed). Reconnects
 * with capped exponential backoff; the pipeline never sees the difference.
 */
public final class LiveTcpSource implements AisSource {

    private static final Logger log = LoggerFactory.getLogger(LiveTcpSource.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private final SourceProperties.LiveTcp config;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public LiveTcpSource(SourceProperties.LiveTcp config) {
        this.config = config;
    }

    @Override
    public void start(Consumer<TimestampedLine> sink) {
        running.set(true);
        worker = Thread.ofVirtual().name("live-tcp-source").start(() -> run(sink));
    }

    private void run(Consumer<TimestampedLine> sink) {
        long backoffMs = 1000;
        while (running.get()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(config.host(), config.port()), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                log.info("Connected to AIS feed {}:{}", config.host(), config.port());
                backoffMs = 1000;

                var reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        sink.accept(new TimestampedLine(Instant.now(), line));
                    }
                }
            } catch (IOException e) {
                if (!running.get()) {
                    return;
                }
                log.warn("AIS feed connection lost ({}); reconnecting in {} ms", e.getMessage(), backoffMs);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
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
        return "live-tcp:" + config.host() + ":" + config.port();
    }
}
