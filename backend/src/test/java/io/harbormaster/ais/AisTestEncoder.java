package io.harbormaster.ais;

/**
 * Test-only inverse of the decoder: packs bit fields per ITU-R M.1371 and
 * emits a six-bit-armored payload. Implemented independently of
 * {@link BitReader} (write-path bit math instead of read-path) so a
 * symmetric offset mistake cannot cancel itself out in round-trip tests.
 */
final class AisTestEncoder {

    private final StringBuilder bits = new StringBuilder();

    AisTestEncoder unsigned(long value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            bits.append((value >> i) & 1);
        }
        return this;
    }

    AisTestEncoder signed(long value, int length) {
        return unsigned(value & ((1L << length) - 1), length);
    }

    AisTestEncoder string(String text, int length) {
        int chars = length / 6;
        for (int i = 0; i < chars; i++) {
            char c = i < text.length() ? text.charAt(i) : '@';
            int v = c >= 64 ? c - 64 : c; // '@'+letters -> 0..31, space/digits -> 32..63
            unsigned(v, 6);
        }
        return this;
    }

    /** Pads to a six-bit boundary and returns payload + fill bit count. */
    Encoded build() {
        int fill = (6 - bits.length() % 6) % 6;
        bits.append("0".repeat(fill));
        StringBuilder payload = new StringBuilder(bits.length() / 6);
        for (int i = 0; i < bits.length(); i += 6) {
            int v = Integer.parseInt(bits.substring(i, i + 6), 2);
            payload.append((char) (v < 40 ? v + 48 : v + 56));
        }
        return new Encoded(payload.toString(), fill);
    }

    record Encoded(String payload, int fillBits) {
    }
}
