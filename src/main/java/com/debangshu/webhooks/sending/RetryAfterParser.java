package com.debangshu.webhooks.sending;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Retry-After can be a number of seconds ("120") or an HTTP date ("Fri, 13 Nov 2026 10:00:00 GMT").
 * Returns null when the header is missing or can't be read, so the normal backoff is used.
 */
public final class RetryAfterParser {

    private RetryAfterParser() {
    }

    public static Duration parse(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                Instant until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration wait = Duration.between(now, until);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
