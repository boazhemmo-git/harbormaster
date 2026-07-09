package io.harbormaster.ais;

/**
 * Type 5 — Class A static and voyage-related data (two-fragment message).
 *
 * @param shipType   ITU ship-type code (e.g. 70–79 cargo, 80–89 tanker)
 * @param draughtM   maximum present static draught in metres
 */
public record StaticVoyageData(
        int mmsi,
        long imoNumber,
        String callsign,
        String name,
        int shipType,
        int dimToBowM,
        int dimToSternM,
        int dimToPortM,
        int dimToStarboardM,
        double draughtM,
        String destination) implements AisMessage {

    @Override
    public int type() {
        return 5;
    }
}
