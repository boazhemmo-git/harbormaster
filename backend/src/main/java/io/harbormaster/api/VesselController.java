package io.harbormaster.api;

import io.harbormaster.detection.Alert;
import io.harbormaster.detection.AlertLog;
import io.harbormaster.tracking.TrackStore;
import io.harbormaster.tracking.VesselTrack;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/vessels")
public class VesselController {

    public record VesselDetail(VesselDto vessel, List<Alert> alerts) {
    }

    private final TrackStore trackStore;
    private final AlertLog alertLog;

    public VesselController(TrackStore trackStore, AlertLog alertLog) {
        this.trackStore = trackStore;
        this.alertLog = alertLog;
    }

    /** Full snapshot with trails — the initial state for a connecting client. */
    @GetMapping
    public List<VesselDto> all() {
        return trackStore.all().stream()
                .map(track -> VesselDto.from(track, true))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{mmsi}")
    public ResponseEntity<VesselDetail> byMmsi(@PathVariable int mmsi) {
        VesselTrack track = trackStore.get(mmsi);
        if (track == null || track.latestFix() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new VesselDetail(
                VesselDto.from(track, true),
                alertLog.recentForVessel(mmsi, 20)));
    }
}
