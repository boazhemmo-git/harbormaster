package io.harbormaster.api;

import io.harbormaster.detection.Alert;
import io.harbormaster.detection.AlertLog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertLog alertLog;

    public AlertController(AlertLog alertLog) {
        this.alertLog = alertLog;
    }

    @GetMapping
    public List<Alert> recent(@RequestParam(defaultValue = "100") int limit) {
        return alertLog.recent(Math.clamp(limit, 1, 1000));
    }
}
