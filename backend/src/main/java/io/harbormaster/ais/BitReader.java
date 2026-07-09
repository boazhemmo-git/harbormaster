package io.harbormaster.ais;

/**
 * Reads bit fields from a six-bit-armored AIS payload (ITU-R M.1371).
 *
 * <p>Each payload character encodes 6 bits: subtract 48 from the ASCII value,
 * and if the result is greater than 40 subtract 8 more. Bits are ordered
 * MSB-first across the whole payload, so bit {@code i} lives at position
 * {@code 5 - (i % 6)} of six-bit group {@code i / 6}.
 */
public final class BitReader {

    private final byte[] sixBitGroups;
    private final int bitLength;

    public BitReader(String payload, int fillBits) {
        this.sixBitGroups = new byte[payload.length()];
        for (int i = 0; i < payload.length(); i++) {
            int v = payload.charAt(i) - 48;
            if (v > 40) {
                v -= 8;
            }
            if (v < 0 || v > 63) {
                throw new AisDecodeException("Invalid six-bit character '" + payload.charAt(i) + "' at index " + i);
            }
            sixBitGroups[i] = (byte) v;
        }
        this.bitLength = payload.length() * 6 - fillBits;
    }

    public int bitLength() {
        return bitLength;
    }

    /** Reads an unsigned big-endian integer of up to 63 bits. */
    public long readUnsigned(int start, int length) {
        checkRange(start, length);
        long value = 0;
        for (int i = start; i < start + length; i++) {
            value = (value << 1) | bit(i);
        }
        return value;
    }

    /** Reads a two's-complement signed big-endian integer. */
    public long readSigned(int start, int length) {
        long value = readUnsigned(start, length);
        long signBit = 1L << (length - 1);
        return (value & signBit) != 0 ? value - (1L << length) : value;
    }

    /**
     * Reads a six-bit ASCII string. Values below 32 map to {@code '@' + value}
     * (uppercase letters), values 32–63 map to themselves (space, digits,
     * punctuation). {@code '@'} terminates the string; trailing spaces are
     * trimmed.
     */
    public String readString(int start, int length) {
        StringBuilder sb = new StringBuilder(length / 6);
        for (int offset = 0; offset + 6 <= length && start + offset + 6 <= bitLength; offset += 6) {
            int v = (int) readUnsigned(start + offset, 6);
            if (v == 0) {
                break; // '@' — end of string
            }
            sb.append((char) (v < 32 ? v + 64 : v));
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        return sb.substring(0, end);
    }

    private int bit(int index) {
        return (sixBitGroups[index / 6] >> (5 - (index % 6))) & 1;
    }

    private void checkRange(int start, int length) {
        if (length < 1 || length > 63 || start < 0 || start + length > bitLength) {
            throw new AisDecodeException(
                    "Bit range [" + start + ", " + (start + length) + ") outside payload of " + bitLength + " bits");
        }
    }
}
