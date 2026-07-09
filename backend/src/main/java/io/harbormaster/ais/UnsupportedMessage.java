package io.harbormaster.ais;

/**
 * A structurally valid AIS message of a type this pipeline does not consume
 * (base-station reports, binary broadcasts, aids-to-navigation, ...). Kept as
 * a first-class value so ingest statistics can account for every sentence.
 */
public record UnsupportedMessage(int type, int mmsi) implements AisMessage {
}
