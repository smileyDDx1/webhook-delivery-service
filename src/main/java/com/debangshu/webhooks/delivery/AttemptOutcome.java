package com.debangshu.webhooks.delivery;

public enum AttemptOutcome {
    /** 2xx response */
    SUCCESS,
    /** the receiver answered, but not with 2xx */
    HTTP_ERROR,
    /** no response within app.delivery.http-timeout */
    TIMEOUT,
    /** could not connect at all (DNS, connection refused, TLS...) */
    NETWORK_ERROR,
    /** the worker stopped (crash, deploy) before it saved a result; written by the lease reaper */
    LEASE_EXPIRED
}
