package io.harbormaster.api;

import io.harbormaster.detection.Alert;
import io.harbormaster.detection.DetectionEngine;
import io.harbormaster.pipeline.PipelineService;
import io.harbormaster.pipeline.PipelineStats;
import io.harbormaster.tracking.TrackStore;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pushes three frame types to WebSocket clients:
 * <ul>
 *   <li>{@code positions} — batched dirty-track deltas, once per second</li>
 *   <li>{@code alert} — immediately as detectors fire</li>
 *   <li>{@code stats} — pipeline counters, every five seconds</li>
 * </ul>
 */
@Component
public class LiveBroadcaster {

    private final LiveWebSocketHandler handler;
    private final TrackStore trackStore;
    private final DetectionEngine detectionEngine;
    private final PipelineService pipeline;
    private final PipelineStats stats;

    public LiveBroadcaster(LiveWebSocketHandler handler, TrackStore trackStore,
                           DetectionEngine detectionEngine, PipelineService pipeline, PipelineStats stats) {
        this.handler = handler;
        this.trackStore = trackStore;
        this.detectionEngine = detectionEngine;
        this.pipeline = pipeline;
        this.stats = stats;
    }

    @PostConstruct
    void subscribeToAlerts() {
        detectionEngine.subscribe(this::pushAlert);
    }

    private void pushAlert(Alert alert) {
        handler.broadcast(Map.of("type", "alert", "alert", alert));
    }

    @Scheduled(fixedRateString = "PT1S")
    void pushPositions() {
        if (handler.sessionCount() == 0) {
            return;
        }
        List<VesselDto> updates = new ArrayList<>();
        for (var track : trackStore.all()) {
            if (track.consumeDirty()) {
                VesselDto dto = VesselDto.from(track, true);
                if (dto != null) {
                    updates.add(dto);
                }
            }
        }
        updates.removeIf(Objects::isNull);
        if (!updates.isEmpty()) {
            handler.broadcast(Map.of("type", "positions", "updates", updates));
        }
    }

    @Scheduled(fixedRateString = "PT5S")
    void pushStats() {
        if (handler.sessionCount() == 0) {
            return;
        }
        var latency = stats.latencyPercentiles();
        handler.broadcast(Map.of(
                "type", "stats",
                "stats", Map.of(
                        "mode", pipeline.mode().name(),
                        "messagesPerSec", Math.round(
                                stats.messagesPerSecond(Instant.now().getEpochSecond()) * 10) / 10.0,
                        "decoded", stats.messagesDecoded.sum(),
                        "decodeErrors", stats.decodeErrors.sum(),
                        "vessels", trackStore.size(),
                        "latencyP99Micros", latency.p99Micros())));
    }
}
