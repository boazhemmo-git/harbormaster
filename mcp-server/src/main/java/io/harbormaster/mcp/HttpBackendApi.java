package io.harbormaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** {@link BackendApi} over HTTP using the JDK's built-in client. */
public final class HttpBackendApi implements BackendApi {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;

    public HttpBackendApi(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public JsonNode stats() throws IOException {
        return get("/api/stats");
    }

    @Override
    public JsonNode vessels() throws IOException {
        return get("/api/vessels");
    }

    @Override
    public JsonNode vessel(int mmsi) throws IOException {
        return get("/api/vessels/" + mmsi);
    }

    @Override
    public JsonNode alerts(int limit) throws IOException {
        return get("/api/alerts?limit=" + limit);
    }

    private JsonNode get(String path) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IOException("backend returned HTTP " + response.statusCode() + " for " + path);
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling backend", e);
        }
    }
}
