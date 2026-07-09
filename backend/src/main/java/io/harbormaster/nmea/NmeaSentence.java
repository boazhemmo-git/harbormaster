package io.harbormaster.nmea;

/**
 * A single parsed and checksum-verified AIS NMEA sentence (AIVDM/AIVDO and
 * base-station variants such as BSVDM). One AIS message may span multiple
 * sentences; see {@link FragmentAssembler}.
 *
 * @param fragmentCount total fragments composing the message
 * @param fragmentNumber 1-based index of this fragment
 * @param sequenceId    sequential message id linking fragments ("" when absent)
 * @param channel       AIS radio channel ("A"/"B", may be empty)
 * @param payload       six-bit armored payload characters
 * @param fillBits      number of padding bits appended to the last character
 */
public record NmeaSentence(
        int fragmentCount,
        int fragmentNumber,
        String sequenceId,
        String channel,
        String payload,
        int fillBits) {
}
