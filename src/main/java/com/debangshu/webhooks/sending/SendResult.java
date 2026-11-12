package com.debangshu.webhooks.sending;

import com.debangshu.webhooks.delivery.AttemptOutcome;

import java.time.Duration;

/** What happened when we sent one request. statusCode is null when no response came back at all. */
public record SendResult(AttemptOutcome outcome, Integer statusCode, String responseBody, String error,
                         Duration retryAfter, int durationMs) {

    private static final int MAX_ERROR_LENGTH = 500;

    public static SendResult response(int statusCode, String responseBody, Duration retryAfter, int durationMs) {
        boolean ok = statusCode >= 200 && statusCode < 300;
        return new SendResult(ok ? AttemptOutcome.SUCCESS : AttemptOutcome.HTTP_ERROR, statusCode, responseBody,
                ok ? null : "Receiver answered HTTP " + statusCode, retryAfter, durationMs);
    }

    public static SendResult timeout(String error, int durationMs) {
        return new SendResult(AttemptOutcome.TIMEOUT, null, null, truncate(error), null, durationMs);
    }

    public static SendResult networkError(String error, int durationMs) {
        return new SendResult(AttemptOutcome.NETWORK_ERROR, null, null, truncate(error), null, durationMs);
    }

    public boolean success() {
        return outcome == AttemptOutcome.SUCCESS;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH);
    }
}
