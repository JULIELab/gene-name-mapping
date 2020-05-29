package de.julielab.jules.ae.genemapping.utils;

public class GeneMapperRuntimeException extends RuntimeException {
    public GeneMapperRuntimeException() {
    }

    public GeneMapperRuntimeException(String message) {
        super(message);
    }

    public GeneMapperRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }

    public GeneMapperRuntimeException(Throwable cause) {
        super(cause);
    }

    public GeneMapperRuntimeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
