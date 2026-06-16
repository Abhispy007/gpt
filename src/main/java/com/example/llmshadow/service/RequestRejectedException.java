package com.example.llmshadow.service;

public class RequestRejectedException extends RuntimeException {

    private final Reason reason;

    public RequestRejectedException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_REQUEST,
        REQUEST_TOO_LARGE,
        RATE_LIMITED,
        BACKPRESSURE,
        STREAMING_NOT_SUPPORTED
    }
}
