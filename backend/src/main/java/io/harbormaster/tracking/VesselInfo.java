package io.harbormaster.tracking;

/**
 * Static vessel identity, accumulated from type 5 / type 24 messages and
 * merged field-by-field as parts arrive (Class B splits identity across two
 * independent transmissions).
 */
public record VesselInfo(
        String name,
        String callsign,
        int shipType,
        int lengthM,
        int beamM,
        double draughtM,
        String destination,
        long imoNumber) {

    public static final VesselInfo UNKNOWN = new VesselInfo(null, null, -1, -1, -1, Double.NaN, null, -1);

    public VesselInfo mergeName(String newName) {
        return newName == null || newName.isBlank() ? this
                : new VesselInfo(newName, callsign, shipType, lengthM, beamM, draughtM, destination, imoNumber);
    }

    public VesselInfo mergeCallsignAndType(String newCallsign, int newShipType) {
        return new VesselInfo(
                name,
                newCallsign != null && !newCallsign.isBlank() ? newCallsign : callsign,
                newShipType > 0 ? newShipType : shipType,
                lengthM, beamM, draughtM, destination, imoNumber);
    }

    public VesselInfo mergeVoyage(String newName, String newCallsign, int newShipType,
                                  int newLengthM, int newBeamM, double newDraughtM,
                                  String newDestination, long newImo) {
        return new VesselInfo(
                newName != null && !newName.isBlank() ? newName : name,
                newCallsign != null && !newCallsign.isBlank() ? newCallsign : callsign,
                newShipType > 0 ? newShipType : shipType,
                newLengthM > 0 ? newLengthM : lengthM,
                newBeamM > 0 ? newBeamM : beamM,
                !Double.isNaN(newDraughtM) && newDraughtM > 0 ? newDraughtM : draughtM,
                newDestination != null && !newDestination.isBlank() ? newDestination : destination,
                newImo > 0 ? newImo : imoNumber);
    }

    /** True for cargo (70–79) and tanker (80–89) ITU type codes. */
    public boolean isHighInterestType() {
        return shipType >= 70 && shipType <= 89;
    }

    /** Pilot/tug/port-service types that legitimately loiter and meet vessels. */
    public boolean isPortServiceType() {
        return shipType >= 50 && shipType <= 56;
    }
}
