package io.harbormaster.ais;

/**
 * Decoded AIS message hierarchy. Sealed so the pipeline's pattern-matching
 * switch is exhaustive — adding a message type is a compile-time checklist,
 * not a runtime surprise.
 */
public sealed interface AisMessage
        permits PositionReport, StaticVoyageData, StaticDataReport, UnsupportedMessage {

    int type();

    int mmsi();
}
