package io.harbormaster.mcp;

/**
 * Entry point: wires the live backend API client into the MCP server and
 * serves the Model Context Protocol over stdio.
 *
 * <p>Stdout belongs exclusively to the protocol — all diagnostics go to
 * stderr, which MCP clients surface as server logs.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String apiBase = System.getenv().getOrDefault("HARBORMASTER_API", "http://localhost:8080");
        System.err.println("harbormaster-mcp: serving MCP over stdio, backend at " + apiBase);

        var tools = new ToolRegistry(new HttpBackendApi(apiBase));
        var server = new McpServer("harbormaster", "0.1.0", tools);
        server.serve(System.in, System.out);
    }
}
