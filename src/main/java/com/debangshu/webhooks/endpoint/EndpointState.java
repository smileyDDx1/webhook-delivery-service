package com.debangshu.webhooks.endpoint;

/**
 * What the API shows:
 * ACTIVE   - deliveries are sent normally
 * PAUSED   - the circuit breaker is open after too many failures in a row, deliveries wait until paused_until
 * DISABLED - turned off by the user, or because the receiver answered 410 Gone. Nothing is sent.
 */
public enum EndpointState {
    ACTIVE,
    PAUSED,
    DISABLED
}
