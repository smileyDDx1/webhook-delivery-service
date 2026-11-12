package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.delivery.DeliveryQueue.ClaimedDelivery;
import com.debangshu.webhooks.endpoint.Endpoint;
import com.debangshu.webhooks.event.Event;
import com.debangshu.webhooks.sending.SendResult;
import com.debangshu.webhooks.sending.WebhookHttpSender;
import com.debangshu.webhooks.sending.WebhookPayloads;
import com.debangshu.webhooks.signing.WebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Handles one claimed delivery: load it -> sign -> send -> save the result.
 *
 * The three steps are separate on purpose:
 * 1. load: short read-only transaction
 * 2. send: NO transaction and no DB connection (the receiver may take seconds)
 * 3. save: short write transaction (DeliveryResultRecorder)
 */
@Component
public class DeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryProcessor.class);

    private final DeliveryRepository deliveries;
    private final WebhookPayloads payloads;
    private final WebhookSigner signer;
    private final WebhookHttpSender sender;
    private final DeliveryResultRecorder recorder;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;

    public DeliveryProcessor(DeliveryRepository deliveries, WebhookPayloads payloads, WebhookSigner signer,
                             WebhookHttpSender sender, DeliveryResultRecorder recorder,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.deliveries = deliveries;
        this.payloads = payloads;
        this.signer = signer;
        this.sender = sender;
        this.recorder = recorder;
        this.clock = clock;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public void process(ClaimedDelivery claimed) {
        // MDC adds the delivery id to every log line written while this delivery is handled
        MDC.put("deliveryId", String.valueOf(claimed.id()));
        try {
            DeliveryTask task = readOnlyTransaction.execute(status -> loadTask(claimed));
            if (task == null) {
                return;
            }
            Instant startedAt = clock.instant();
            String body = payloads.envelope(task.webhookId(), task.eventType(), task.eventCreatedAt(), task.payload());
            Map<String, String> headers = signer.headers(task.webhookId(), body, task.secrets(), startedAt);
            SendResult result = sender.send(task.url(), headers, body);
            recorder.record(task, result, startedAt);
        } catch (OptimisticLockingFailureException e) {
            log.info("Delivery {} was changed by someone else while it was being sent, result dropped", claimed.id());
        } catch (RuntimeException e) {
            // nothing is lost: the delivery stays SENDING, its lease runs out and the reaper queues it again
            log.error("Could not process delivery {}, it will be retried after its lease expires", claimed.id(), e);
        } finally {
            MDC.remove("deliveryId");
        }
    }

    private DeliveryTask loadTask(ClaimedDelivery claimed) {
        Delivery delivery = deliveries.findWithEventAndEndpoint(claimed.id()).orElse(null);
        if (delivery == null || delivery.getVersion() != claimed.version()) {
            return null; // deleted, or already changed by something else
        }
        Endpoint endpoint = delivery.getEndpoint();
        Event event = delivery.getEvent();
        return new DeliveryTask(delivery.getId(), delivery.getVersion(), delivery.getAttemptCount(),
                endpoint.getId(), endpoint.getUrl(), endpoint.signingSecrets(clock.instant()),
                event.getId(), event.webhookId(), event.getEventType(), event.getPayload(), event.getCreatedAt());
    }
}
