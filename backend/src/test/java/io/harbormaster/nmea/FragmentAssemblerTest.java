package io.harbormaster.nmea;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FragmentAssemblerTest {

    private final FragmentAssembler assembler = new FragmentAssembler();
    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void singleFragmentPassesThrough() {
        var result = assembler.offer(sentence(1, 1, "7", "PAYLOAD", 0), t0);

        assertThat(result).contains(new FragmentAssembler.AssembledMessage("PAYLOAD", 0));
    }

    @Test
    void twoFragmentsAssembleInOrder() {
        assertThat(assembler.offer(sentence(2, 1, "3", "AAAA", 0), t0)).isEmpty();

        var result = assembler.offer(sentence(2, 2, "3", "BB", 2), t0);

        assertThat(result).contains(new FragmentAssembler.AssembledMessage("AAAABB", 2));
    }

    @Test
    void interleavedSequencesDoNotCrossContaminate() {
        assertThat(assembler.offer(sentence(2, 1, "1", "X1", 0), t0)).isEmpty();
        assertThat(assembler.offer(sentence(2, 1, "2", "Y1", 0), t0)).isEmpty();

        assertThat(assembler.offer(sentence(2, 2, "2", "Y2", 4), t0))
                .contains(new FragmentAssembler.AssembledMessage("Y1Y2", 4));
        assertThat(assembler.offer(sentence(2, 2, "1", "X2", 0), t0))
                .contains(new FragmentAssembler.AssembledMessage("X1X2", 0));
    }

    @Test
    void staleIncompleteGroupIsEvicted() {
        assertThat(assembler.offer(sentence(2, 1, "5", "OLD", 0), t0)).isEmpty();

        // 60s later the first fragment must be gone; a new group under the
        // same key assembles from scratch.
        Instant later = t0.plusSeconds(60);
        assertThat(assembler.offer(sentence(2, 1, "5", "NEW1", 0), later)).isEmpty();
        assertThat(assembler.offer(sentence(2, 2, "5", "NEW2", 0), later))
                .contains(new FragmentAssembler.AssembledMessage("NEW1NEW2", 0));
    }

    private static NmeaSentence sentence(int count, int number, String seq, String payload, int fill) {
        return new NmeaSentence(count, number, seq, "A", payload, fill);
    }
}
