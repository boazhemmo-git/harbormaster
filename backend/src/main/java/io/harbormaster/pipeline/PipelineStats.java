package io.harbormaster.pipeline;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free pipeline counters plus a small sliding latency window for
 * percentile estimation. Precise enough for a live dashboard; a production
 * deployment would export Micrometer histograms instead (the actuator
 * endpoint is already wired for that).
 */
@Component
public class PipelineStats {

    private static final int RATE_SLOTS = 60;
    private static final int LATENCY_WINDOW = 2048;

    public final LongAdder linesIn = new LongAdder();
    public final LongAdder linesDropped = new LongAdder();
    public final LongAdder sentencesParsed = new LongAdder();
    public final LongAdder checksumOrFormatRejected = new LongAdder();
    public final LongAdder messagesDecoded = new LongAdder();
    public final LongAdder decodeErrors = new LongAdder();
    public final LongAdder positionsApplied = new LongAdder();
    public final LongAdder unsupportedMessages = new LongAdder();

    private final Instant startedAt = Instant.now();

    // Per-second rate ring: slot = epochSecond % RATE_SLOTS, cleared when the
    // wall clock advances onto a stale slot.
    private final AtomicLongArray rateBuckets = new AtomicLongArray(RATE_SLOTS);
    private final AtomicLongArray rateBucketEpochs = new AtomicLongArray(RATE_SLOTS);

    // Latency samples in microseconds, overwritten round-robin.
    private final AtomicLongArray latencySamples = new AtomicLongArray(LATENCY_WINDOW);
    private final AtomicLong latencyCursor = new AtomicLong();

    public void markDecoded(long epochSecond) {
        messagesDecoded.increment();
        int slot = (int) (epochSecond % RATE_SLOTS);
        long stamped = rateBucketEpochs.get(slot);
        if (stamped != epochSecond) {
            if (rateBucketEpochs.compareAndSet(slot, stamped, epochSecond)) {
                rateBuckets.set(slot, 0);
            }
        }
        rateBuckets.incrementAndGet(slot);
    }

    public void recordLatencyMicros(long micros) {
        int index = (int) (latencyCursor.getAndIncrement() % LATENCY_WINDOW);
        latencySamples.set(index, micros);
    }

    /** Messages per second averaged over the trailing full minute. */
    public double messagesPerSecond(long nowEpochSecond) {
        long total = 0;
        int liveSlots = 0;
        for (int slot = 0; slot < RATE_SLOTS; slot++) {
            long epoch = rateBucketEpochs.get(slot);
            if (epoch > 0 && nowEpochSecond - epoch < RATE_SLOTS && epoch != nowEpochSecond) {
                total += rateBuckets.get(slot);
                liveSlots++;
            }
        }
        return liveSlots == 0 ? 0 : (double) total / liveSlots;
    }

    public record LatencyPercentiles(long p50Micros, long p99Micros) {
    }

    public LatencyPercentiles latencyPercentiles() {
        int filled = (int) Math.min(latencyCursor.get(), LATENCY_WINDOW);
        if (filled == 0) {
            return new LatencyPercentiles(0, 0);
        }
        long[] copy = new long[filled];
        for (int i = 0; i < filled; i++) {
            copy[i] = latencySamples.get(i);
        }
        Arrays.sort(copy);
        return new LatencyPercentiles(
                copy[(int) (filled * 0.50)],
                copy[Math.min(filled - 1, (int) (filled * 0.99))]);
    }

    public long uptimeSeconds() {
        return Instant.now().getEpochSecond() - startedAt.getEpochSecond();
    }
}
