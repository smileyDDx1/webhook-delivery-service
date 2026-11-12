package com.debangshu.webhooks.delivery;

import com.debangshu.webhooks.config.AppProperties;
import com.debangshu.webhooks.config.AppProperties.ExecutorType;
import com.debangshu.webhooks.delivery.DeliveryQueue.ClaimedDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The background part of the service. Every poll-interval it:
 * 1. claims due deliveries from the database (DeliveryQueue.claimDue, FOR UPDATE SKIP LOCKED)
 * 2. hands each one to a sender thread (DeliveryProcessor)
 *
 * Sender threads are virtual threads (Java 21) by default. A webhook request spends almost all its time
 * waiting for the receiver, and a waiting virtual thread costs a few KB instead of ~1 MB for a platform
 * thread, so hundreds of slow receivers don't need hundreds of OS threads. The Semaphore still caps how
 * many requests are in flight (max-concurrency), so a big backlog can't open 50,000 connections at once.
 *
 * SmartLifecycle lets Spring start the worker after everything else is ready and stop it first on
 * shutdown, before the database connection pool is closed.
 */
@Component
public class DeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryQueue queue;
    private final DeliveryProcessor processor;
    private final AppProperties.Delivery settings;

    private ScheduledExecutorService scheduler;
    private ExecutorService senders;
    private Semaphore inFlight;
    private volatile boolean running;
    private volatile boolean paused;

    @Autowired
    public DeliveryWorker(DeliveryQueue queue, DeliveryProcessor processor, AppProperties properties) {
        this(queue, processor, properties.delivery());
    }

    /** Used by the benchmark to run workers with different settings. */
    public DeliveryWorker(DeliveryQueue queue, DeliveryProcessor processor, AppProperties.Delivery settings) {
        this.queue = queue;
        this.processor = processor;
        this.settings = settings;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!settings.workerEnabled()) {
            log.info("Delivery worker is disabled (app.delivery.worker-enabled=false)");
            return;
        }
        senders = settings.executorType() == ExecutorType.VIRTUAL
                ? Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("delivery-", 0).factory())
                : Executors.newFixedThreadPool(settings.maxConcurrency(), Thread.ofPlatform().name("delivery-", 0).factory());
        inFlight = new Semaphore(settings.maxConcurrency());
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("delivery-poller").daemon().factory());
        running = true;

        long pollMillis = settings.pollInterval().toMillis();
        long reaperMillis = settings.reaperInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, pollMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::reapSafely, reaperMillis, reaperMillis, TimeUnit.MILLISECONDS);
        log.info("Delivery worker started ({} threads, up to {} requests in flight, polling every {} ms)",
                settings.executorType(), settings.maxConcurrency(), pollMillis);
    }

    /**
     * Graceful shutdown: stop claiming new work, then give the requests that are already running time
     * to finish and save their result. Anything still running after that is picked up again by the lease
     * reaper of another instance (or this one after the restart).
     */
    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        scheduler.shutdownNow();
        senders.shutdown();
        try {
            long waitMillis = settings.httpTimeout().toMillis() + 5_000;
            if (!senders.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                log.warn("Some deliveries were still running at shutdown, their leases will expire and they will be retried");
                senders.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            senders.shutdownNow();
        }
        log.info("Delivery worker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops claiming new deliveries without shutting down (used by tests that need a quiet queue). */
    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    /**
     * One poll. Keeps claiming batches while there is due work and free capacity, so a large backlog
     * is worked off at full speed instead of one batch per poll interval.
     */
    int pollOnce() {
        int dispatched = 0;
        while (running && !paused) {
            int capacity = Math.min(settings.batchSize(), inFlight.availablePermits());
            if (capacity == 0) {
                break; // every slot is busy; the next poll tries again
            }
            List<ClaimedDelivery> claimed = queue.claimDue(capacity, settings.leaseDuration());
            for (ClaimedDelivery delivery : claimed) {
                // only this poller thread takes permits, so the permits counted above are still free
                inFlight.acquireUninterruptibly();
                try {
                    senders.execute(() -> {
                        try {
                            processor.process(delivery);
                        } finally {
                            inFlight.release();
                        }
                    });
                    dispatched++;
                } catch (RejectedExecutionException shuttingDown) {
                    inFlight.release(); // the lease will expire and the delivery gets sent again later
                }
            }
            if (claimed.size() < capacity) {
                break; // nothing more is due right now
            }
        }
        return dispatched;
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            // an exception would cancel the scheduled task for good, so log it and keep polling
            log.error("Polling for due deliveries failed", e);
        }
    }

    private void reapSafely() {
        try {
            int requeued = queue.requeueExpiredLeases();
            if (requeued > 0) {
                log.warn("Put {} deliveries with an expired lease back in the queue", requeued);
            }
        } catch (RuntimeException e) {
            log.error("Lease reaper failed", e);
        }
    }
}
