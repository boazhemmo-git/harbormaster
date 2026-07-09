package io.harbormaster.ais;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes AIS messages into NMEA AIVDM sentences — the write path of the
 * protocol, used by the scenario simulator so demo traffic exercises the
 * exact same wire format, fragmentation and checksum handling as a live
 * feed. (The unit tests keep their own independent bit-packer on purpose;
 * see {@code AisTestEncoder}.)
 */
public final class AisEncoder {

    private static final int MAX_PAYLOAD_CHARS_PER_SENTENCE = 56;

    private final StringBuilder bits = new StringBuilder(512);

    public static AisEncoder message() {
        return new AisEncoder();
    }

    public AisEncoder unsigned(long value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            bits.append((value >> i) & 1);
        }
        return this;
    }

    public AisEncoder signed(long value, int length) {
        return unsigned(value & ((1L << length) - 1), length);
    }

    public AisEncoder string(String text, int lengthBits) {
        String upper = text == null ? "" : text.toUpperCase();
        int chars = lengthBits / 6;
        for (int i = 0; i < chars; i++) {
            char c = i < upper.length() ? upper.charAt(i) : '@';
            int v = c >= 64 ? c - 64 : c;
            unsigned(v & 0x3F, 6);
        }
        return this;
    }

    /** Encodes a type 1 position report (168 bits, single sentence). */
    public static AisEncoder positionReport(int mmsi, int navStatus, double sogKn,
                                            double lat, double lon, double cogDeg,
                                            int heading, int utcSecond) {
        return message()
                .unsigned(1, 6)
                .unsigned(0, 2)
                .unsigned(mmsi, 30)
                .unsigned(navStatus & 0xF, 4)
                .signed(0, 8)
                .unsigned(Double.isNaN(sogKn) ? 1023 : Math.min(1022, Math.round(sogKn * 10)), 10)
                .unsigned(0, 1)
                .signed(Math.round(lon * 600_000), 28)
                .signed(Math.round(lat * 600_000), 27)
                .unsigned(Double.isNaN(cogDeg) ? 3600 : Math.round(cogDeg * 10) % 3600, 12)
                .unsigned(heading < 0 ? 511 : heading % 360, 9)
                .unsigned(utcSecond, 6)
                .unsigned(0, 2)
                .unsigned(0, 3)
                .unsigned(0, 1)
                .unsigned(0, 19);
    }

    /** Encodes a type 5 static and voyage message (424 bits, two sentences). */
    public static AisEncoder staticVoyage(int mmsi, long imo, String callsign, String name,
                                          int shipType, int dimBow, int dimStern,
                                          int dimPort, int dimStarboard, double draughtM,
                                          String destination) {
        return message()
                .unsigned(5, 6)
                .unsigned(0, 2)
                .unsigned(mmsi, 30)
                .unsigned(0, 2)
                .unsigned(imo, 30)
                .string(callsign, 42)
                .string(name, 120)
                .unsigned(shipType, 8)
                .unsigned(dimBow, 9)
                .unsigned(dimStern, 9)
                .unsigned(dimPort, 6)
                .unsigned(dimStarboard, 6)
                .unsigned(1, 4)
                .unsigned(0, 20)      // ETA (not simulated)
                .unsigned(Math.round(draughtM * 10), 8)
                .string(destination, 120)
                .unsigned(0, 1)
                .unsigned(0, 1);
    }

    /** Wraps the accumulated bits into one or more checksummed AIVDM sentences. */
    public List<String> toSentences(char channel, int sequenceId) {
        int fill = (6 - bits.length() % 6) % 6;
        StringBuilder padded = new StringBuilder(bits).append("0".repeat(fill));

        StringBuilder payload = new StringBuilder(padded.length() / 6);
        for (int i = 0; i < padded.length(); i += 6) {
            int v = Integer.parseInt(padded.substring(i, i + 6), 2);
            payload.append((char) (v < 40 ? v + 48 : v + 56));
        }

        int fragments = (payload.length() + MAX_PAYLOAD_CHARS_PER_SENTENCE - 1) / MAX_PAYLOAD_CHARS_PER_SENTENCE;
        List<String> sentences = new ArrayList<>(fragments);
        for (int frag = 0; frag < fragments; frag++) {
            int from = frag * MAX_PAYLOAD_CHARS_PER_SENTENCE;
            int to = Math.min(payload.length(), from + MAX_PAYLOAD_CHARS_PER_SENTENCE);
            boolean last = frag == fragments - 1;
            String seq = fragments == 1 ? "" : String.valueOf(sequenceId % 10);
            String body = "AIVDM,%d,%d,%s,%c,%s,%d".formatted(
                    fragments, frag + 1, seq, channel, payload.substring(from, to), last ? fill : 0);
            sentences.add("!" + body + "*" + "%02X".formatted(checksum(body)));
        }
        return sentences;
    }

    private static int checksum(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum ^= body.charAt(i);
        }
        return sum;
    }
}
