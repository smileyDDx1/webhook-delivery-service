package com.debangshu.webhooks.endpoint;

/** What is stored in the database. "Paused" is not stored as a status, it comes from paused_until. */
public enum EndpointStatus {
    ACTIVE,
    DISABLED
}
