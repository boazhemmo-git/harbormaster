package io.harbormaster.ais;

/**
 * Type 24 — Class B static data report. Transmitted in two independent parts:
 * part A carries the vessel name, part B the ship type and callsign. Fields
 * not present in the received part are null / -1.
 */
public record StaticDataReport(
        int mmsi,
        int partNumber,
        String name,
        String callsign,
        int shipType) implements AisMessage {

    @Override
    public int type() {
        return 24;
    }
}
