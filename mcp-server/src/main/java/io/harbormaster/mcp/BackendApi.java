package io.harbormaster.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * The slice of the Harbormaster REST API the tools consume. An interface so
 * tool logic is testable without a running backend.
 */
public interface BackendApi {

    JsonNode stats() throws IOException;

    JsonNode vessels() throws IOException;

    JsonNode vessel(int mmsi) throws IOException;

    JsonNode alerts(int limit) throws IOException;
}
