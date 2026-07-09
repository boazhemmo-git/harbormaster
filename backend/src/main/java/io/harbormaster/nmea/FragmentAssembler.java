package io.harbormaster.nmea;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reassembles multi-sentence AIS messages (e.g. type 5 static/voyage data
 * spans two sentences). Fragments are keyed by radio channel + sequence id;
 * incomplete groups are evicted after a timeout so a lost fragment cannot
 * poison the key for subsequent messages.
 *
 * <p>Not thread-safe by design: the pipeline runs a single decode worker, and
 * fragment groups from a serial feed arrive back-to-back. Documented here so
 * a future parallel-decode change knows to shard by channel.
 */
public final class FragmentAssembler {

    /** Assembled payload ready for six-bit decoding. */
    public record AssembledMessage(String payload, int fillBits) {
    }

    private record PendingKey(String channel, String sequenceId) {
    }

    private static final class Pending {
        final String[] payloads;
        final Instant firstSeen;
        int received;
        int fillBits;

        Pending(int fragmentCount, Instant now) {
            this.payloads = new String[fragmentCount];
            this.firstSeen = now;
        }
    }

    private static final Duration EVICT_AFTER = Duration.ofSeconds(30);

    private final Map<PendingKey, Pending> pending = new HashMap<>();

    /**
     * Offers a sentence; returns the fully assembled message once all
     * fragments of its group have arrived.
     */
    public Optional<AssembledMessage> offer(NmeaSentence sentence, Instant now) {
        if (sentence.fragmentCount() <= 1) {
            return Optional.of(new AssembledMessage(sentence.payload(), sentence.fillBits()));
        }
        evictStale(now);

        var key = new PendingKey(sentence.channel(), sentence.sequenceId());
        Pending group = pending.computeIfAbsent(key, k -> new Pending(sentence.fragmentCount(), now));

        int idx = sentence.fragmentNumber() - 1;
        if (idx < 0 || idx >= group.payloads.length) {
            pending.remove(key);
            return Optional.empty();
        }
        if (group.payloads[idx] == null) {
            group.payloads[idx] = sentence.payload();
            group.received++;
        }
        if (sentence.fragmentNumber() == sentence.fragmentCount()) {
            group.fillBits = sentence.fillBits();
        }
        if (group.received < group.payloads.length) {
            return Optional.empty();
        }

        pending.remove(key);
        return Optional.of(new AssembledMessage(String.join("", group.payloads), group.fillBits));
    }

    private void evictStale(Instant now) {
        pending.values().removeIf(p -> Duration.between(p.firstSeen, now).compareTo(EVICT_AFTER) > 0);
    }
}
