package io.harbormaster.detection;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded in-memory alert history (newest first). A ring of the most recent
 * {@value #CAPACITY} alerts is plenty for a live monitoring view; anything
 * longer-lived belongs in a real store (see ADR-0003).
 */
@Component
public class AlertLog {

    private static final int CAPACITY = 1000;

    private final ConcurrentLinkedDeque<Alert> alerts = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Map<AlertType, AtomicInteger> countsByType = new EnumMap<>(AlertType.class);

    public AlertLog() {
        for (AlertType type : AlertType.values()) {
            countsByType.put(type, new AtomicInteger());
        }
    }

    public void add(Alert alert) {
        alerts.addFirst(alert);
        countsByType.get(alert.type()).incrementAndGet();
        if (size.incrementAndGet() > CAPACITY) {
            alerts.pollLast();
            size.decrementAndGet();
        }
    }

    public List<Alert> recent(int limit) {
        List<Alert> result = new ArrayList<>(Math.min(limit, size.get()));
        for (Alert alert : alerts) {
            if (result.size() >= limit) {
                break;
            }
            result.add(alert);
        }
        return result;
    }

    public List<Alert> recentForVessel(int mmsi, int limit) {
        List<Alert> result = new ArrayList<>();
        for (Alert alert : alerts) {
            if (result.size() >= limit) {
                break;
            }
            if (alert.mmsi() == mmsi) {
                result.add(alert);
            }
        }
        return result;
    }

    public Map<AlertType, Integer> totalsByType() {
        Map<AlertType, Integer> totals = new EnumMap<>(AlertType.class);
        countsByType.forEach((type, count) -> totals.put(type, count.get()));
        return totals;
    }
}
