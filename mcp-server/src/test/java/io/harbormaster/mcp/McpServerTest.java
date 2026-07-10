package io.harbormaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol-level tests: raw JSON-RPC lines in, raw responses out — the same
 * bytes an MCP client would exchange.
 */
class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpServer server = new McpServer("harbormaster", "0.1.0", new ToolRegistry(new FakeApi()));

    @Test
    void initializeEchoesClientProtocolVersion() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""");

        JsonNode node = JSON.readTree(response);
        assertThat(node.path("id").asInt()).isEqualTo(1);
        assertThat(node.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(node.path("result").path("serverInfo").path("name").asText()).isEqualTo("harbormaster");
        assertThat(node.path("result").path("capabilities").has("tools")).isTrue();
    }

    @Test
    void notificationsGetNoResponse() {
        assertThat(server.handleLine("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""")).isNull();
    }

    @Test
    void toolsListExposesFiveToolsWithSchemas() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");

        JsonNode tools = JSON.readTree(response).path("result").path("tools");
        assertThat(tools).hasSize(5);
        for (JsonNode tool : tools) {
            assertThat(tool.path("name").asText()).isNotBlank();
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        }
    }

    @Test
    void fleetOverviewFormatsStats() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_fleet_overview","arguments":{}}}""");

        String text = firstText(response);
        assertThat(text).contains("Vessels tracked: 42")
                .contains("ACTIVE: 40")
                .contains("17.5 msg/s")
                .contains("AIS_GAP=2");
    }

    @Test
    void findDarkShipsReportsLostTracksAndGapAlerts() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_dark_ships","arguments":{}}}""");

        String text = firstText(response);
        assertThat(text).contains("GHOST TANKER")
                .contains("MMSI 244000001")
                .contains("Recent AIS-gap alerts");
    }

    @Test
    void findVesselsFiltersByCategoryAndSpeed() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"find_vessels","arguments":{"category":"cargo","min_speed_kn":10}}}""");

        String text = firstText(response);
        assertThat(text).contains("FAST FREIGHTER").doesNotContain("GHOST TANKER").doesNotContain("SLOW CARGO");
    }

    @Test
    void getVesselReturnsNotFoundForUnknownMmsi() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_vessel","arguments":{"mmsi":999999999}}}""");

        assertThat(firstText(response)).contains("No tracked vessel with MMSI 999999999");
    }

    @Test
    void unknownToolIsAnInToolError() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"nope","arguments":{}}}""");

        JsonNode node = JSON.readTree(response);
        assertThat(node.path("result").path("isError").asBoolean()).isTrue();
        assertThat(firstText(response)).contains("Unknown tool");
    }

    @Test
    void unknownMethodIsJsonRpcMethodNotFound() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":8,"method":"resources/list"}""");

        assertThat(JSON.readTree(response).path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void malformedJsonIsParseError() throws IOException {
        String response = server.handleLine("{not json");

        assertThat(JSON.readTree(response).path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void pingReturnsEmptyResult() throws IOException {
        String response = server.handleLine("""
                {"jsonrpc":"2.0","id":9,"method":"ping"}""");

        JsonNode node = JSON.readTree(response);
        assertThat(node.path("result").isObject()).isTrue();
        assertThat(node.has("error")).isFalse();
    }

    private static String firstText(String response) throws IOException {
        return JSON.readTree(response).path("result").path("content").get(0).path("text").asText();
    }

    /** Canned backend covering every field the tools read. */
    private static final class FakeApi implements BackendApi {

        @Override
        public JsonNode stats() throws IOException {
            return JSON.readTree("""
                    {"source":"simulation:test","mode":"SIMULATION","messagesPerSec":17.5,
                     "decoded":1000,"decodeErrors":0,"vessels":42,
                     "vesselsByState":{"ACTIVE":40,"COASTING":1,"LOST":1},
                     "alertsByType":{"AIS_GAP":2,"KINEMATIC_ANOMALY":1,"LOITERING":0,"RENDEZVOUS":1},
                     "latencyP99Micros":1500}""");
        }

        @Override
        public JsonNode vessels() throws IOException {
            return JSON.readTree("""
                    [{"mmsi":244000001,"name":"GHOST TANKER","shipType":81,"state":"LOST",
                      "lat":52.5,"lon":3.5,"sogKn":11.0,"lastSeen":"2026-07-10T00:00:00Z"},
                     {"mmsi":244000002,"name":"FAST FREIGHTER","shipType":70,"state":"ACTIVE",
                      "lat":52.1,"lon":3.9,"sogKn":14.2,"destination":"ROTTERDAM"},
                     {"mmsi":244000003,"name":"SLOW CARGO","shipType":70,"state":"ACTIVE",
                      "lat":52.0,"lon":3.7,"sogKn":4.0}]""");
        }

        @Override
        public JsonNode vessel(int mmsi) {
            return null; // exercised via the not-found path
        }

        @Override
        public JsonNode alerts(int limit) throws IOException {
            return JSON.readTree("""
                    [{"id":"a1","time":"2026-07-10T00:05:00Z","type":"AIS_GAP","severity":"CRITICAL",
                      "mmsi":244000001,"vesselName":"GHOST TANKER","lat":52.5,"lon":3.5,
                      "message":"GHOST TANKER went dark while underway at 11.0 kn","details":{}},
                     {"id":"a2","time":"2026-07-10T00:02:00Z","type":"RENDEZVOUS","severity":"WARNING",
                      "mmsi":244000002,"vesselName":"FAST FREIGHTER","lat":52.1,"lon":3.9,
                      "message":"two vessels holding station","details":{}}]""");
        }
    }
}
