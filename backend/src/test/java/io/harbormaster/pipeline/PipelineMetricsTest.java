package io.harbormaster.pipeline;

import io.harbormaster.detection.AlertLog;
import io.harbormaster.tracking.TrackStore;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link PipelineStats} counters are bridged onto Micrometer and
 * render in Prometheus exposition format — using a real
 * {@link PrometheusMeterRegistry}, no web app boot required. (The
 * {@code /actuator/prometheus} endpoint wiring and the {@code ais.process}
 * observation timer are Spring/Micrometer plumbing, verified against a running
 * instance.)
 */
class PipelineMetricsTest {

    @Test
    void rendersPipelineCountersInPrometheusFormat() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        var stats = new PipelineStats();
        TrackStore trackStore = mock(TrackStore.class);
        AlertLog alertLog = mock(AlertLog.class);
        when(trackStore.all()).thenReturn(List.of());
        when(trackStore.size()).thenReturn(4);
        when(alertLog.totalsByType()).thenReturn(Map.of());

        new PipelineMetrics(registry, stats, trackStore, alertLog);

        // Simulate some pipeline activity, then scrape.
        stats.linesIn.add(10);
        stats.sentencesParsed.add(9);
        for (int i = 0; i < 7; i++) {
            stats.markDecoded(Instant.now().getEpochSecond());
        }
        stats.positionsApplied.add(6);

        String scrape = registry.scrape();

        assertThat(scrape)
                .contains("harbormaster_lines_received_total 10.0")
                .contains("harbormaster_messages_decoded_total 7.0")
                .contains("harbormaster_positions_applied_total 6.0")
                .contains("harbormaster_vessels 4.0");

        // Tagged gauges register at zero, so every Grafana series exists from t0.
        assertThat(scrape)
                .contains("harbormaster_vessels_by_state{state=\"ACTIVE\"}")
                .contains("harbormaster_alerts{type=\"RENDEZVOUS\"}");
    }
}
