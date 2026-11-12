package com.debangshu.webhooks.delivery;

import java.time.Instant;
import java.util.List;

/**
 * Everything needed to send one delivery, copied out of the entities while the transaction is open.
 * The HTTP request then runs WITHOUT a transaction or database connection: a receiver that takes
 * 10 seconds to answer must not keep a connection from the pool busy for 10 seconds.
 */
public record DeliveryTask(
        long deliveryId,
        long version,
        int attemptNumber,
        long endpointId,
        String url,
        List<String> secrets,
        long eventId,
        String webhookId,
        String eventType,
        String payload,
        Instant eventCreatedAt) {
}
