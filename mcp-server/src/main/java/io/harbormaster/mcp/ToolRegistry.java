package io.harbormaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The five tools Harbormaster offers to AI agents, each a thin, readable
 * projection of the public REST API into plain text an LLM can reason over.
 */
public final class ToolRegistry {

    public record Tool(String name, String description, String inputSchemaJson) {
    }

    public static class UnknownToolException extends RuntimeException {
        UnknownToolException(String name) {
            super("Unknown tool: " + name);
        }
    }

    private static final String NO_ARGS_SCHEMA = """
            {"type":"object","properties":{},"additionalProperties":false}""";

    private final BackendApi api;

    public ToolRegistry(BackendApi api) {
        this.api = api;
    }

    public List<Tool> all() {
        return List.of(
                new Tool("get_fleet_overview",
                        "Live overview of the tracked maritime picture: vessel counts by tracking state, "
                                + "pipeline throughput and alert totals.",
                        NO_ARGS_SCHEMA),
                new Tool("find_dark_ships",
                        "Vessels that have gone dark: tracks declared LOST plus recent AIS-gap alerts — "
                                + "ships that stopped transmitting while underway, with their last known position.",
                        NO_ARGS_SCHEMA),
                new Tool("list_alerts",
                        "Recent anomaly alerts, newest first. Optionally filter by type "
                                + "(AIS_GAP, KINEMATIC_ANOMALY, LOITERING, RENDEZVOUS) or severity "
                                + "(INFO, WARNING, CRITICAL).",
                        """
                        {"type":"object","properties":{
                          "type":{"type":"string","enum":["AIS_GAP","KINEMATIC_ANOMALY","LOITERING","RENDEZVOUS"]},
                          "severity":{"type":"string","enum":["INFO","WARNING","CRITICAL"]},
                          "limit":{"type":"integer","minimum":1,"maximum":100,"default":20}
                        },"additionalProperties":false}"""),
                new Tool("find_vessels",
                        "Search tracked vessels by name/MMSI fragment and/or filter by category "
                                + "(cargo, tanker, passenger, fishing, service, other) or minimum speed in knots.",
                        """
                        {"type":"object","properties":{
                          "query":{"type":"string","description":"case-insensitive fragment of vessel name or MMSI"},
                          "category":{"type":"string","enum":["cargo","tanker","passenger","fishing","service","other"]},
                          "min_speed_kn":{"type":"number","minimum":0},
                          "limit":{"type":"integer","minimum":1,"maximum":50,"default":15}
                        },"additionalProperties":false}"""),
                new Tool("get_vessel",
                        "Full detail for one vessel by MMSI: identity, dimensions, destination, kinematics, "
                                + "tracking state and its recent alerts.",
                        """
                        {"type":"object","properties":{
                          "mmsi":{"type":"integer","description":"9-digit Maritime Mobile Service Identity"}
                        },"required":["mmsi"],"additionalProperties":false}"""));
    }

    public String call(String name, JsonNode args) throws IOException {
        return switch (name) {
            case "get_fleet_overview" -> fleetOverview();
            case "find_dark_ships" -> darkShips();
            case "list_alerts" -> listAlerts(args);
            case "find_vessels" -> findVessels(args);
            case "get_vessel" -> getVessel(args);
            default -> throw new UnknownToolException(name);
        };
    }

    private String fleetOverview() throws IOException {
        JsonNode stats = api.stats();
        var sb = new StringBuilder();
        sb.append("Fleet overview (source: ").append(stats.path("source").asText()).append(")\n");
        sb.append("- Vessels tracked: ").append(stats.path("vessels").asInt());
        JsonNode byState = stats.path("vesselsByState");
        if (byState.isObject() && !byState.isEmpty()) {
            sb.append(" (");
            var parts = new ArrayList<String>();
            byState.properties().forEach(e -> parts.add(e.getKey() + ": " + e.getValue().asInt()));
            sb.append(String.join(", ", parts)).append(")");
        }
        sb.append('\n');
        sb.append("- Throughput: ").append(stats.path("messagesPerSec").asDouble()).append(" msg/s, ")
                .append(stats.path("decoded").asLong()).append(" decoded total, ")
                .append(stats.path("decodeErrors").asLong()).append(" decode errors\n");
        sb.append("- Pipeline latency p99: ").append(stats.path("latencyP99Micros").asLong()).append(" µs\n");
        sb.append("- Alerts so far:");
        JsonNode alerts = stats.path("alertsByType");
        alerts.properties().forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue().asInt()));
        return sb.toString();
    }

    private String darkShips() throws IOException {
        JsonNode vessels = api.vessels();
        var lines = new ArrayList<String>();
        for (JsonNode v : vessels) {
            if ("LOST".equals(v.path("state").asText())) {
                lines.add("- %s (MMSI %d, %s): last seen %s at %.4f, %.4f — was doing %s kn".formatted(
                        nameOf(v), v.path("mmsi").asInt(), categoryOf(v.path("shipType").asInt(-1)),
                        v.path("lastSeen").asText("unknown"),
                        v.path("lat").asDouble(), v.path("lon").asDouble(),
                        v.path("sogKn").isNumber() ? "%.1f".formatted(v.path("sogKn").asDouble()) : "?"));
            }
        }
        JsonNode alerts = api.alerts(100);
        var gapAlerts = new ArrayList<String>();
        for (JsonNode a : alerts) {
            if ("AIS_GAP".equals(a.path("type").asText()) && gapAlerts.size() < 10) {
                gapAlerts.add("- [" + a.path("time").asText() + "] " + a.path("message").asText());
            }
        }
        if (lines.isEmpty() && gapAlerts.isEmpty()) {
            return "No dark ships right now: no LOST tracks and no recent AIS-gap alerts.";
        }
        var sb = new StringBuilder();
        sb.append(lines.isEmpty() ? "No tracks currently in LOST state.\n"
                : "Tracks currently LOST (" + lines.size() + "):\n" + String.join("\n", lines) + "\n");
        if (!gapAlerts.isEmpty()) {
            sb.append("Recent AIS-gap alerts:\n").append(String.join("\n", gapAlerts));
        }
        return sb.toString();
    }

    private String listAlerts(JsonNode args) throws IOException {
        String type = args.path("type").asText("");
        String severity = args.path("severity").asText("");
        int limit = args.path("limit").asInt(20);
        JsonNode alerts = api.alerts(100);
        var lines = new ArrayList<String>();
        for (JsonNode a : alerts) {
            if (!type.isEmpty() && !type.equals(a.path("type").asText())) {
                continue;
            }
            if (!severity.isEmpty() && !severity.equals(a.path("severity").asText())) {
                continue;
            }
            lines.add("- [%s] %s/%s: %s".formatted(
                    a.path("time").asText(), a.path("type").asText(),
                    a.path("severity").asText(), a.path("message").asText()));
            if (lines.size() >= limit) {
                break;
            }
        }
        return lines.isEmpty() ? "No alerts match." : String.join("\n", lines);
    }

    private String findVessels(JsonNode args) throws IOException {
        String query = args.path("query").asText("").toLowerCase(Locale.ROOT);
        String category = args.path("category").asText("");
        double minSpeed = args.path("min_speed_kn").asDouble(-1);
        int limit = args.path("limit").asInt(15);

        var lines = new ArrayList<String>();
        int matched = 0;
        for (JsonNode v : api.vessels()) {
            String name = nameOf(v).toLowerCase(Locale.ROOT);
            String mmsi = String.valueOf(v.path("mmsi").asInt());
            if (!query.isEmpty() && !name.contains(query) && !mmsi.contains(query)) {
                continue;
            }
            if (!category.isEmpty() && !category.equals(categoryOf(v.path("shipType").asInt(-1)))) {
                continue;
            }
            if (minSpeed >= 0 && (!v.path("sogKn").isNumber() || v.path("sogKn").asDouble() < minSpeed)) {
                continue;
            }
            matched++;
            if (lines.size() < limit) {
                lines.add("- %s (MMSI %s, %s) — %s kn, state %s, at %.4f, %.4f%s".formatted(
                        nameOf(v), mmsi, categoryOf(v.path("shipType").asInt(-1)),
                        v.path("sogKn").isNumber() ? "%.1f".formatted(v.path("sogKn").asDouble()) : "?",
                        v.path("state").asText(),
                        v.path("lat").asDouble(), v.path("lon").asDouble(),
                        v.path("destination").isTextual() && !v.path("destination").asText().isEmpty()
                                ? ", bound for " + v.path("destination").asText() : ""));
            }
        }
        if (matched == 0) {
            return "No vessels match.";
        }
        String header = matched + " vessel(s) match" + (matched > lines.size()
                ? " (showing first " + lines.size() + ")" : "") + ":\n";
        return header + String.join("\n", lines);
    }

    private String getVessel(JsonNode args) throws IOException {
        int mmsi = args.path("mmsi").asInt(0);
        if (mmsi <= 0) {
            return "Provide a valid MMSI (9-digit integer).";
        }
        JsonNode detail = api.vessel(mmsi);
        if (detail == null) {
            return "No tracked vessel with MMSI " + mmsi + ".";
        }
        JsonNode v = detail.path("vessel");
        var sb = new StringBuilder();
        sb.append(nameOf(v)).append(" — MMSI ").append(mmsi).append('\n');
        sb.append("- Type: ").append(categoryOf(v.path("shipType").asInt(-1)))
                .append(" (code ").append(v.path("shipType").asInt(-1)).append(")\n");
        if (v.path("callsign").isTextual()) {
            sb.append("- Callsign: ").append(v.path("callsign").asText()).append('\n');
        }
        if (v.path("lengthM").asInt(-1) > 0) {
            sb.append("- Size: ").append(v.path("lengthM").asInt()).append(" x ")
                    .append(v.path("beamM").asInt()).append(" m\n");
        }
        if (v.path("destination").isTextual() && !v.path("destination").asText().isEmpty()) {
            sb.append("- Destination: ").append(v.path("destination").asText()).append('\n');
        }
        sb.append("- Position: %.4f, %.4f | Speed: %s kn | Course: %s°\n".formatted(
                v.path("lat").asDouble(), v.path("lon").asDouble(),
                v.path("sogKn").isNumber() ? "%.1f".formatted(v.path("sogKn").asDouble()) : "?",
                v.path("cogDeg").isNumber() ? String.valueOf(Math.round(v.path("cogDeg").asDouble())) : "?"));
        sb.append("- Tracking state: ").append(v.path("state").asText())
                .append(", last seen ").append(v.path("lastSeen").asText()).append('\n');
        JsonNode alerts = detail.path("alerts");
        if (alerts.isArray() && !alerts.isEmpty()) {
            sb.append("- Alerts for this vessel:\n");
            for (JsonNode a : alerts) {
                sb.append("  - [").append(a.path("time").asText()).append("] ")
                        .append(a.path("type").asText()).append(": ")
                        .append(a.path("message").asText()).append('\n');
            }
        } else {
            sb.append("- No alerts for this vessel.\n");
        }
        return sb.toString();
    }

    private static String nameOf(JsonNode vessel) {
        JsonNode name = vessel.path("name");
        return name.isTextual() && !name.asText().isBlank() ? name.asText()
                : "MMSI " + vessel.path("mmsi").asInt();
    }

    private static String categoryOf(int shipType) {
        if (shipType >= 70 && shipType <= 79) {
            return "cargo";
        }
        if (shipType >= 80 && shipType <= 89) {
            return "tanker";
        }
        if (shipType >= 60 && shipType <= 69) {
            return "passenger";
        }
        if (shipType == 30) {
            return "fishing";
        }
        if (shipType >= 50 && shipType <= 56) {
            return "service";
        }
        return "other";
    }
}
