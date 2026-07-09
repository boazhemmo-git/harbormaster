package io.harbormaster.nmea;

import java.util.Optional;

/**
 * Parses raw AIS NMEA 0183 lines ({@code !AIVDM}/{@code !AIVDO} and talker
 * variants) into {@link NmeaSentence}s, verifying the checksum.
 *
 * <p>Lines may carry an NMEA v4 TAG block prefix
 * ({@code \s:station,c:epoch*hh\!AIVDM,...}) as emitted by shore-station
 * aggregation feeds; the block is stripped before parsing.
 */
public final class NmeaParser {

    private NmeaParser() {
    }

    /**
     * @return the parsed sentence, or empty if the line is not a VDM/VDO
     *         sentence or fails structural/checksum validation
     */
    public static Optional<NmeaSentence> parse(String rawLine) {
        String line = stripTagBlock(rawLine.strip());
        if (line.length() < 10 || line.charAt(0) != '!') {
            return Optional.empty();
        }
        int star = line.lastIndexOf('*');
        if (star < 0 || star + 3 > line.length()) {
            return Optional.empty();
        }
        if (!checksumValid(line, star)) {
            return Optional.empty();
        }

        String[] fields = line.substring(0, star).split(",", -1);
        // fields[0] = "!xxVDM" — accept any talker (AI, BS, AB, ...) with VDM/VDO type
        if (fields.length < 7 || !(fields[0].endsWith("VDM") || fields[0].endsWith("VDO"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new NmeaSentence(
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    fields[3],
                    fields[4],
                    fields[5],
                    Integer.parseInt(fields[6])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String stripTagBlock(String line) {
        if (line.startsWith("\\")) {
            int end = line.indexOf('\\', 1);
            if (end > 0) {
                return line.substring(end + 1);
            }
        }
        return line;
    }

    private static boolean checksumValid(String line, int star) {
        int expected;
        try {
            expected = Integer.parseInt(line.substring(star + 1, star + 3), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int actual = 0;
        for (int i = 1; i < star; i++) {
            actual ^= line.charAt(i);
        }
        return actual == expected;
    }
}
