package io.harbormaster.ais;

/**
 * Decodes assembled six-bit AIS payloads into typed {@link AisMessage}s,
 * implemented from the ITU-R M.1371-5 bit layouts — deliberately without a
 * decoding library, since bit-exact protocol work is the point of this
 * project.
 *
 * <p>Supported types: 1/2/3 (Class A position), 5 (static &amp; voyage),
 * 18/19 (Class B position), 24 (Class B static). Everything else decodes to
 * {@link UnsupportedMessage} so callers can keep honest statistics.
 */
public final class AisDecoder {

    private static final double COORD_SCALE = 600_000.0; // 1/10000 arc-minute units per degree
    private static final long LON_NOT_AVAILABLE = 181 * 600_000L;
    private static final long LAT_NOT_AVAILABLE = 91 * 600_000L;
    private static final int SOG_NOT_AVAILABLE = 1023;
    private static final int COG_NOT_AVAILABLE = 3600;
    private static final int HEADING_NOT_AVAILABLE = 511;

    private AisDecoder() {
    }

    /**
     * @throws AisDecodeException when the payload is too short for its
     *                            claimed type or contains invalid characters
     */
    public static AisMessage decode(String payload, int fillBits) {
        BitReader bits = new BitReader(payload, fillBits);
        if (bits.bitLength() < 38) {
            throw new AisDecodeException("Payload of " + bits.bitLength() + " bits too short for any message");
        }
        int type = (int) bits.readUnsigned(0, 6);
        int mmsi = (int) bits.readUnsigned(8, 30);

        return switch (type) {
            case 1, 2, 3 -> decodeClassAPosition(type, mmsi, bits);
            case 5 -> decodeStaticVoyage(mmsi, bits);
            case 18 -> decodeClassBPosition(type, mmsi, bits, false);
            case 19 -> decodeClassBPosition(type, mmsi, bits, true);
            case 24 -> decodeStaticDataReport(mmsi, bits);
            default -> new UnsupportedMessage(type, mmsi);
        };
    }

    private static PositionReport decodeClassAPosition(int type, int mmsi, BitReader bits) {
        require(bits, 149, "class A position report");
        return new PositionReport(
                type,
                mmsi,
                (int) bits.readUnsigned(38, 4),
                speed(bits.readUnsigned(50, 10)),
                longitude(bits.readSigned(61, 28)),
                latitude(bits.readSigned(89, 27)),
                course(bits.readUnsigned(116, 12)),
                heading(bits.readUnsigned(128, 9)),
                (int) bits.readUnsigned(137, 6),
                null);
    }

    private static PositionReport decodeClassBPosition(int type, int mmsi, BitReader bits, boolean extended) {
        require(bits, 139, "class B position report");
        String name = null;
        if (extended && bits.bitLength() >= 263) {
            name = bits.readString(143, 120);
        }
        return new PositionReport(
                type,
                mmsi,
                -1, // Class B has no navigation status
                speed(bits.readUnsigned(46, 10)),
                longitude(bits.readSigned(57, 28)),
                latitude(bits.readSigned(85, 27)),
                course(bits.readUnsigned(112, 12)),
                heading(bits.readUnsigned(124, 9)),
                (int) bits.readUnsigned(133, 6),
                name);
    }

    private static StaticVoyageData decodeStaticVoyage(int mmsi, BitReader bits) {
        require(bits, 302, "static and voyage data");
        // Destination (bits 302-421) is legitimately truncated by some transponders.
        int destinationBits = Math.min(120, bits.bitLength() - 302);
        String destination = destinationBits >= 6 ? bits.readString(302, destinationBits - destinationBits % 6) : "";
        return new StaticVoyageData(
                mmsi,
                bits.readUnsigned(40, 30),
                bits.readString(70, 42),
                bits.readString(112, 120),
                (int) bits.readUnsigned(232, 8),
                (int) bits.readUnsigned(240, 9),
                (int) bits.readUnsigned(249, 9),
                (int) bits.readUnsigned(258, 6),
                (int) bits.readUnsigned(264, 6),
                bits.readUnsigned(294, 8) / 10.0,
                destination);
    }

    private static StaticDataReport decodeStaticDataReport(int mmsi, BitReader bits) {
        require(bits, 40, "static data report");
        int part = (int) bits.readUnsigned(38, 2);
        if (part == 0) {
            require(bits, 160, "static data report part A");
            return new StaticDataReport(mmsi, 0, bits.readString(40, 120), null, -1);
        }
        require(bits, 132, "static data report part B");
        return new StaticDataReport(
                mmsi,
                1,
                null,
                bits.readString(90, 42),
                (int) bits.readUnsigned(40, 8));
    }

    private static double longitude(long raw) {
        return raw == LON_NOT_AVAILABLE ? Double.NaN : raw / COORD_SCALE;
    }

    private static double latitude(long raw) {
        return raw == LAT_NOT_AVAILABLE ? Double.NaN : raw / COORD_SCALE;
    }

    private static double speed(long raw) {
        return raw == SOG_NOT_AVAILABLE ? Double.NaN : raw / 10.0;
    }

    private static double course(long raw) {
        return raw == COG_NOT_AVAILABLE ? Double.NaN : raw / 10.0;
    }

    private static int heading(long raw) {
        return raw == HEADING_NOT_AVAILABLE ? -1 : (int) raw;
    }

    private static void require(BitReader bits, int minBits, String what) {
        if (bits.bitLength() < minBits) {
            throw new AisDecodeException(
                    "Payload of " + bits.bitLength() + " bits too short for " + what + " (needs " + minBits + ")");
        }
    }
}
