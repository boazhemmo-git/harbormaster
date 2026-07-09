package io.harbormaster.detection;

import io.harbormaster.tracking.Fix;
import io.harbormaster.tracking.TrackState;
import io.harbormaster.tracking.VesselTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Fans pipeline events out to every registered {@link AnomalyDetector} and
 * funnels their alerts into the {@link AlertLog} plus any live subscribers
 * (the WebSocket broadcaster). Detector failures are contained: one broken
 * rule must never stall the ingest pipeline.
 */
@Component
public class DetectionEngine {

    private static final Logger log = LoggerFactory.getLogger(DetectionEngine.class);

    private final List<AnomalyDetector> detectors;
    private final AlertLog alertLog;
    private final List<Consumer<Alert>> subscribers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public DetectionEngine(List<AnomalyDetector> detectors, AlertLog alertLog) {
        this.detectors = detectors;
        this.alertLog = alertLog;
    }

    public void subscribe(Consumer<Alert> subscriber) {
        subscribers.add(subscriber);
    }

    public void onFix(VesselTrack track, Fix previous, Fix current) {
        for (AnomalyDetector detector : detectors) {
            try {
                detector.onFix(track, previous, current, this::publish);
            } catch (RuntimeException e) {
                log.warn("Detector {} failed on fix for MMSI {}", detector.getClass().getSimpleName(),
                        track.mmsi(), e);
            }
        }
    }

    public void onStateChange(VesselTrack track, TrackState from, TrackState to) {
        for (AnomalyDetector detector : detectors) {
            try {
                detector.onStateChange(track, from, to, this::publish);
            } catch (RuntimeException e) {
                log.warn("Detector {} failed on state change for MMSI {}", detector.getClass().getSimpleName(),
                        track.mmsi(), e);
            }
        }
    }

    public void onSweep(Collection<VesselTrack> tracks, Instant now) {
        for (AnomalyDetector detector : detectors) {
            try {
                detector.onSweep(tracks, now, this::publish);
            } catch (RuntimeException e) {
                log.warn("Detector {} failed on sweep", detector.getClass().getSimpleName(), e);
            }
        }
    }

    private void publish(Alert alert) {
        alertLog.add(alert);
        log.info("ALERT [{}/{}] {}", alert.type(), alert.severity(), alert.message());
        for (Consumer<Alert> subscriber : subscribers) {
            try {
                subscriber.accept(alert);
            } catch (RuntimeException e) {
                log.warn("Alert subscriber failed", e);
            }
        }
    }
}
