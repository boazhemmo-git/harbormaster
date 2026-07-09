package io.harbormaster.ais;

/** Thrown when a payload is structurally invalid for its claimed message type. */
public class AisDecodeException extends RuntimeException {

    public AisDecodeException(String message) {
        super(message);
    }
}
