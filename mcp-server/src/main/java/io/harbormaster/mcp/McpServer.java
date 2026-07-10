package io.harbormaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * A Model Context Protocol server speaking newline-delimited JSON-RPC 2.0
 * over stdio, implemented directly from the MCP specification — in the same
 * spirit as this project's AIS decoder: the protocol layer is small enough
 * that owning it beats depending on it (ADR-0005).
 *
 * <p>Supported: {@code initialize} handshake, {@code ping},
 * {@code tools/list}, {@code tools/call}. Notifications are consumed
 * silently; unknown request methods get a JSON-RPC "method not found".
 */
public final class McpServer {

    private static final String FALLBACK_PROTOCOL_VERSION = "2025-06-18";
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_REQUEST = -32600;
    private static final int PARSE_ERROR = -32700;

    private final String serverName;
    private final String serverVersion;
    private final ToolRegistry tools;
    private final ObjectMapper json = new ObjectMapper();

    public McpServer(String serverName, String serverVersion, ToolRegistry tools) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.tools = tools;
    }

    /** Blocks, serving requests until the input stream closes. */
    public void serve(InputStream in, OutputStream out) {
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        var writer = new PrintWriter(out, false, StandardCharsets.UTF_8);
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String response = handleLine(line);
                if (response != null) {
                    writer.print(response);
                    writer.print('\n');
                    writer.flush();
                }
            }
        } catch (IOException e) {
            System.err.println("harbormaster-mcp: stdin closed (" + e.getMessage() + ")");
        }
    }

    /**
     * Handles one raw JSON-RPC line.
     *
     * @return the serialized response, or null for notifications
     */
    String handleLine(String line) {
        JsonNode message;
        try {
            message = json.readTree(line);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return errorResponse(null, PARSE_ERROR, "invalid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            return errorResponse(null, PARSE_ERROR, "invalid JSON");
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");

        if (id == null || id.isNull()) {
            return null; // notification (e.g. notifications/initialized) — no response
        }
        if (method.isEmpty()) {
            return errorResponse(id, INVALID_REQUEST, "missing method");
        }
        try {
            return switch (method) {
                case "initialize" -> result(id, initializeResult(message.path("params")));
                case "ping" -> result(id, json.createObjectNode());
                case "tools/list" -> result(id, toolsListResult());
                case "tools/call" -> result(id, toolsCallResult(message.path("params")));
                default -> errorResponse(id, METHOD_NOT_FOUND, "unknown method: " + method);
            };
        } catch (Exception e) {
            System.err.println("harbormaster-mcp: " + method + " failed: " + e);
            return errorResponse(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private ObjectNode initializeResult(JsonNode params) {
        // Tools-only servers work identically across protocol revisions, so
        // accept whatever version the client proposes.
        String clientVersion = params.path("protocolVersion").asText(FALLBACK_PROTOCOL_VERSION);
        ObjectNode node = json.createObjectNode();
        node.put("protocolVersion", clientVersion);
        node.putObject("capabilities").putObject("tools");
        ObjectNode info = node.putObject("serverInfo");
        info.put("name", serverName);
        info.put("version", serverVersion);
        return node;
    }

    private ObjectNode toolsListResult() throws IOException {
        ObjectNode node = json.createObjectNode();
        var array = node.putArray("tools");
        for (ToolRegistry.Tool tool : tools.all()) {
            ObjectNode t = array.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", json.readTree(tool.inputSchemaJson()).deepCopy());
        }
        return node;
    }

    private ObjectNode toolsCallResult(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        ObjectNode node = json.createObjectNode();
        var content = node.putArray("content");
        try {
            String text = tools.call(name, arguments);
            content.addObject().put("type", "text").put("text", text);
        } catch (ToolRegistry.UnknownToolException e) {
            node.put("isError", true);
            content.addObject().put("type", "text").put("text", e.getMessage());
        } catch (Exception e) {
            System.err.println("harbormaster-mcp: tool " + name + " failed: " + e);
            node.put("isError", true);
            content.addObject().put("type", "text")
                    .put("text", "Tool failed: " + e.getMessage()
                            + ". Is the Harbormaster backend running? (set HARBORMASTER_API if not on localhost:8080)");
        }
        return node;
    }

    private String result(JsonNode id, ObjectNode resultNode) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.deepCopy());
        response.set("result", resultNode);
        return response.toString();
    }

    private String errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id.deepCopy());
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response.toString();
    }
}
