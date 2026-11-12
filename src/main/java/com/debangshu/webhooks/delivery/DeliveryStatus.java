package com.debangshu.webhooks.delivery;

/**
 * PENDING   -> waiting in the queue (first try, or a retry scheduled for next_attempt_at)
 * SENDING   -> claimed by a worker, the HTTP request is in progress (protected by a lease)
 * SUCCEEDED -> the receiver answered 2xx
 * FAILED    -> gave up: out of retries, 410 Gone, or the endpoint was disabled. Can be replayed.
 *
 * PENDING -> SENDING -> SUCCEEDED
 *               |  \-> PENDING (retry later)
 *               \----> FAILED --(manual retry / replay)--> PENDING
 */
public enum DeliveryStatus {
    PENDING,
    SENDING,
    SUCCEEDED,
    FAILED
}
