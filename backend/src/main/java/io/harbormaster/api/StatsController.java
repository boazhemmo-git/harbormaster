package io.harbormaster.api;

import io.harbormaster.detection.AlertLog;
import io.harbormaster.pipeline.PipelineService;
import io.harbormaster.pipeline.PipelineStats;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.TrackStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@RestController
public class StatsController {

    public record StatsDto(
            String source,
            String mode,
            double messagesPerSec,
            long linesIn,
            long linesDropped,
            long sentencesParsed,
            long rejected,
            long decoded,
            long decodeErrors,
            long positionsApplied,
            long unsupported,
            int vessels,
            Map<TrackState, Long> vesselsByState,
            Map<io.harbormaster.detection.AlertType, Integer> alertsByType,
            long latencyP50Micros,
            long latencyP99Micros,
            long uptimeSec) {
    }

    private final PipelineService pipeline;
    private final PipelineStats stats;
    private final TrackStore trackStore;
    private final AlertLog alertLog;

    public StatsController(PipelineService pipeline, PipelineStats stats,
                           TrackStore trackStore, AlertLog alertLog) {
        this.pipeline = pipeline;
        this.stats = stats;
        this.trackStore = trackStore;
        this.alertLog = alertLog;
    }

    @GetMapping("/api/stats")
    public StatsDto stats() {
        Map<TrackState, Long> byState = new EnumMap<>(TrackState.class);
        trackStore.all().forEach(track -> byState.merge(track.state(), 1L, Long::sum));
        var latency = stats.latencyPercentiles();
        return new StatsDto(
                pipeline.sourceDescription(),
                pipeline.mode().name(),
                Math.round(stats.messagesPerSecond(Instant.now().getEpochSecond()) * 10) / 10.0,
                stats.linesIn.sum(),
                stats.linesDropped.sum(),
                stats.sentencesParsed.sum(),
                stats.checksumOrFormatRejected.sum(),
                stats.messagesDecoded.sum(),
                stats.decodeErrors.sum(),
                stats.positionsApplied.sum(),
                stats.unsupportedMessages.sum(),
                trackStore.size(),
                byState,
                alertLog.totalsByType(),
                latency.p50Micros(),
                latency.p99Micros(),
                stats.uptimeSeconds());
    }
}
