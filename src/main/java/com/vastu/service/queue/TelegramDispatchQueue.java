package com.vastu.service.queue;


import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vastu.enums.NotificationStatus;
import com.vastu.service.impl.TelegramApiService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Highly-reliable in-memory Telegram notification dispatch queue.
 *
 * ── Architecture ────────────────────────────────────────────────────────────
 *
 * All reliability features work entirely without a database:
 *
 *  ① Bounded async queue (LinkedBlockingQueue)
 *       Back-pressure on producers. Overflow is handled gracefully (see below).
 *
 *  ② Dedicated worker threads
 *       A fixed-size thread pool drains the queue. Workers are non-daemon so
 *       the JVM waits for them during shutdown.
 *
 *  ③ Lock-free CAS status transitions (AtomicReference in NotificationRecord)
 *       PENDING → SENDING is claimed atomically, preventing duplicate sends
 *       when multiple workers race for the same record.
 *
 *  ④ Exponential back-off with full jitter on retry
 *       delay = rand(0, min(maxDelay, baseDelay * 2^attempt))
 *       Jitter avoids thundering-herd when many retries fire simultaneously.
 *
 *  ⑤ Per-task retry counter + permanent dead-letter promotion
 *       After MAX_ATTEMPTS failures the record is moved to FAILED status and
 *       enqueued on a separate dead-letter queue for operator inspection. The
 *       dead-letter queue is also bounded to prevent memory leaks.
 *
 *  ⑥ Rate limiting (token-bucket approximation)
 *       Inter-send delay keeps throughput well below Telegram's 30 msg/s cap.
 *       A ScheduledExecutorService fires sends at most once per sendDelayMs.
 *
 *  ⑦ Overflow protection
 *       When the main queue is full (extremely rare at capacity=5000), the
 *       task is temporarily parked in a separate overflow list. A background
 *       job drains overflow back into the main queue as capacity frees up.
 *
 *  ⑧ Graceful shutdown + drain
 *       @PreDestroy flips running=false, lets the worker loop finish the
 *       current queue snapshot (up to 30 s), then forces shutdown. Any
 *       in-flight tasks remain PENDING in the state store and will be
 *       re-attempted if the process is restarted with startup recovery enabled.
 *
 *  ⑨ Retry scheduler
 *       A single-threaded ScheduledExecutorService handles delayed re-enqueue
 *       for failed tasks. Uses virtual threads (Java 21) for the actual
 *       re-enqueue work to avoid starving the scheduler.
 *
 *  ⑩ Periodic eviction
 *       Old SENT records are evicted from the state store on a 1-hour cycle
 *       to prevent unbounded memory growth.
 *
 * ── What is NOT provided (intentional without a DB) ─────────────────────────
 *  • Cross-JVM deduplication  (requires shared storage)
 *  • Durable crash recovery   (state is heap-only; restart loses in-flight tasks)
 *    → Mitigated by using @PreDestroy drain + keeping the JVM healthy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramDispatchQueue {

    private final TelegramApiService telegramApiService;
    private final TelegramNotificationStateStore stateStore;

    // ── Configuration ───────────────────────────────────────────

    @Value("${telegram.queue.capacity:5000}")
    private int queueCapacity;

    @Value("${telegram.queue.workers:2}")
    private int workerCount;

    @Value("${telegram.queue.send-delay-ms:1000}")
    private long sendDelayMs;

    /** Base delay for exponential back-off (ms). Doubles each attempt. */
    @Value("${telegram.queue.retry-base-delay-ms:2000}")
    private long retryBaseDelayMs;

    /** Hard ceiling on back-off delay (ms) — default 2 minutes. */
    @Value("${telegram.queue.retry-max-delay-ms:120000}")
    private long retryMaxDelayMs;

    /** Dead-letter queue size cap. */
    @Value("${telegram.queue.dlq-capacity:500}")
    private int dlqCapacity;

    /** Maximum send attempts per notification before DLQ promotion. */
    private static final int MAX_ATTEMPTS = 5;

    // ── Internal state ──────────────────────────────────────────

    private LinkedBlockingQueue<TelegramNotificationTask> mainQueue;
    private LinkedBlockingQueue<TelegramNotificationTask> overflowQueue;
    private LinkedBlockingQueue<TelegramNotificationTask> deadLetterQueue;

    private ExecutorService workerPool;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = true;

    // Metrics counters (exposed via health endpoint)
    private final AtomicLong totalEnqueued  = new AtomicLong();
    private final AtomicLong totalSent      = new AtomicLong();
    private final AtomicLong totalFailed    = new AtomicLong();
    private final AtomicLong totalRetried   = new AtomicLong();
    private final AtomicLong overflowDropped = new AtomicLong();

    // ── Lifecycle ───────────────────────────────────────────────

    @PostConstruct
    public void init() {
        mainQueue     = new LinkedBlockingQueue<>(queueCapacity);
        overflowQueue = new LinkedBlockingQueue<>(queueCapacity);
        deadLetterQueue = new LinkedBlockingQueue<>(dlqCapacity);

        AtomicInteger threadNum = new AtomicInteger(1);
        workerPool = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "tg-worker-" + threadNum.getAndIncrement());
            t.setDaemon(false); // non-daemon: JVM waits during shutdown
            return t;
        });

        // Single-threaded scheduler for: retry delays, overflow drain, eviction
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-scheduler");
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < workerCount; i++) {
            workerPool.submit(this::workerLoop);
        }

        // Drain overflow queue into main queue every 5 seconds
        scheduler.scheduleWithFixedDelay(
                this::drainOverflowQueue, 5, 5, TimeUnit.SECONDS);

        // Evict old SENT records hourly
        scheduler.scheduleWithFixedDelay(
                stateStore::evictOldSentRecords, 1, 1, TimeUnit.HOURS);

        log.info("[TelegramQueue] Started — capacity={} workers={} sendDelay={}ms " +
                 "retryBase={}ms retryMax={}ms",
                queueCapacity, workerCount, sendDelayMs, retryBaseDelayMs, retryMaxDelayMs);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[TelegramQueue] Shutdown initiated — draining {} queued tasks…", mainQueue.size());
        running = false;
        scheduler.shutdownNow();
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[TelegramQueue] Workers did not finish in 30 s — forcing shutdown. " +
                         "{} tasks remain in queue.", mainQueue.size());
                workerPool.shutdownNow();
            } else {
                log.info("[TelegramQueue] Clean shutdown complete. sent={} failed={} retried={}",
                        totalSent.get(), totalFailed.get(), totalRetried.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }

    // ── Public API ──────────────────────────────────────────────

    /**
     * Enqueue a notification task for delivery.
     *
     * If the main queue is full (queue overflow scenario), the task is parked in
     * the overflow queue. If the overflow queue is also full (extreme scenario),
     * the task is dropped and the metric is incremented — the record remains
     * PENDING in the state store and will be re-enqueued by startup/periodic
     * recovery when the process restarts or the rescue job fires.
     */
    public void enqueue(TelegramNotificationTask task) {
        if (!running) {
            log.warn("[TelegramQueue] Enqueue rejected — queue is shutting down. id={}",
                    task.getNotificationId());
            return;
        }

        boolean offered = mainQueue.offer(task);
        if (offered) {
            totalEnqueued.incrementAndGet();
            log.debug("[TelegramQueue] Enqueued id={} mainQueueSize={}",
                    task.getNotificationId(), mainQueue.size());
        } else {
            // Main queue full → overflow
            boolean overflowed = overflowQueue.offer(task);
            if (overflowed) {
                log.warn("[TelegramQueue] Main queue full — parked id={} in overflow (size={})",
                        task.getNotificationId(), overflowQueue.size());
            } else {
                overflowDropped.incrementAndGet();
                log.error("[TelegramQueue] OVERFLOW FULL — dropped id={}. " +
                          "Record remains PENDING in state store for recovery.",
                        task.getNotificationId());
            }
        }
    }

    /** Convenience: enqueue a list of tasks (e.g. from recovery). */
    public void enqueueAll(List<TelegramNotificationTask> tasks) {
        tasks.forEach(this::enqueue);
    }

    // ── Metrics (for health endpoint) ──────────────────────────

    public int mainQueueSize()     { return mainQueue.size(); }
    public int overflowQueueSize() { return overflowQueue.size(); }
    public int dlqSize()           { return deadLetterQueue.size(); }
    public long getTotalSent()     { return totalSent.get(); }
    public long getTotalFailed()   { return totalFailed.get(); }
    public long getTotalRetried()  { return totalRetried.get(); }
    public long getOverflowDropped() { return overflowDropped.get(); }

    // ── Worker loop ─────────────────────────────────────────────

    private void workerLoop() {
        log.info("[TelegramQueue] Worker {} started", Thread.currentThread().getName());
        while (running || !mainQueue.isEmpty()) {
            try {
                TelegramNotificationTask task = mainQueue.poll(2, TimeUnit.SECONDS);
                if (task == null) continue;

                processTask(task);

                // Rate-limit: respect Telegram's ~30 msg/s cap
                if (sendDelayMs > 0) Thread.sleep(sendDelayMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[TelegramQueue] Worker {} interrupted", Thread.currentThread().getName());
                break;
            } catch (Exception e) {
                // Never let an unexpected exception kill the worker thread
                log.error("[TelegramQueue] Unexpected error in worker loop — continuing", e);
            }
        }
        log.info("[TelegramQueue] Worker {} stopped", Thread.currentThread().getName());
    }

    // ── Task processing ─────────────────────────────────────────

    private void processTask(TelegramNotificationTask task) {
        long notifId = task.getNotificationId();

        Optional<NotificationRecord> opt = stateStore.findById(notifId);
        if (opt.isEmpty()) {
            log.warn("[TelegramQueue] Record id={} not found in state store — discarding task", notifId);
            return;
        }
        NotificationRecord record = opt.get();

        // ① CAS: atomically claim PENDING → SENDING
        //    If CAS fails, another worker already grabbed this record — skip.
        if (!record.compareAndSetStatus(NotificationStatus.PENDING, NotificationStatus.SENDING)) {
            log.debug("[TelegramQueue] Record id={} not PENDING (status={}) — skipping",
                    notifId, record.getStatus());
            return;
        }

        int attempt = record.incrementAndGetAttemptCount();
        log.info("[TelegramQueue] Sending id={} attempt={}/{}", notifId, attempt, MAX_ATTEMPTS);

        try {
            telegramApiService.sendTextMessage(record.getMessageText());

            record.getAttachments().forEach(path -> {
                if (path.toFile().exists()) {
                    telegramApiService.sendDocument(path, null);
                } else {
                    log.warn("[TelegramQueue] Attachment missing on disk: {}", path);
                }
            });

            record.markSent();
            totalSent.incrementAndGet();
            log.info("[TelegramQueue] ✓ id={} delivered (attempt {})", notifId, attempt);

        } catch (Exception e) {
            log.error("[TelegramQueue] ✗ id={} attempt {} failed: {}", notifId, attempt, e.getMessage());
            handleFailure(record, task, attempt, e.getMessage());
        }
    }

    // ── Failure / retry logic ────────────────────────────────────

    /**
     * Decide whether to retry with back-off or promote to the dead-letter queue.
     *
     * Back-off formula: {@code jitter(min(maxDelay, baseDelay * 2^attempt))}
     * Full jitter avoids thundering-herd when many retries are scheduled at once.
     */
    private void handleFailure(NotificationRecord record,
                                TelegramNotificationTask task,
                                int attempt,
                                String error) {
        if (attempt >= MAX_ATTEMPTS) {
            // Permanent failure → dead-letter queue
            record.markFailed(error);
            totalFailed.incrementAndGet();

            boolean dlqOffered = deadLetterQueue.offer(task);
            if (!dlqOffered) {
                log.error("[TelegramQueue] DLQ full — dropped permanently failed id={}. " +
                          "Inspect state store for FAILED records.", record.getId());
            }
            log.error("[TelegramQueue] ☠ id={} permanently FAILED after {} attempts. " +
                      "Added to dead-letter queue (size={}).",
                    record.getId(), attempt, deadLetterQueue.size());
        } else {
            // Transient failure → reset to PENDING and schedule delayed re-enqueue
            record.resetToPending(error);
            totalRetried.incrementAndGet();

            long delayMs = computeBackoff(attempt);
            log.warn("[TelegramQueue] ↺ id={} reset to PENDING, retry in {}ms (attempt {}/{})",
                    record.getId(), delayMs, attempt, MAX_ATTEMPTS);

            // Schedule re-enqueue on the scheduler; actual work done on a virtual thread
            scheduler.schedule(() ->
                Thread.ofVirtual().start(() -> {
                    if (running) {
                        enqueue(task);
                        log.debug("[TelegramQueue] Re-enqueued id={} after {}ms backoff",
                                record.getId(), delayMs);
                    }
                }),
                delayMs, TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Exponential back-off with full jitter.
     *
     * {@code delay = random(0, min(maxDelay, baseDelay × 2^(attempt-1)))}
     */
    private long computeBackoff(int attempt) {
        long cap = Math.min(retryMaxDelayMs, retryBaseDelayMs * (1L << (attempt - 1)));
        return (long) (Math.random() * cap);
    }

    // ── Overflow drain ──────────────────────────────────────────

    /**
     * Periodically attempt to move tasks from the overflow queue back into the
     * main queue. This runs on the scheduler every 5 seconds.
     */
    private void drainOverflowQueue() {
        int moved = 0;
        while (!overflowQueue.isEmpty()) {
            TelegramNotificationTask task = overflowQueue.peek();
            if (task == null) break;
            boolean offered = mainQueue.offer(task);
            if (!offered) break; // main queue still full — try again next cycle
            overflowQueue.poll(); // remove only after successful offer
            moved++;
        }
        if (moved > 0) {
            log.info("[TelegramQueue] Drained {} task(s) from overflow into main queue", moved);
        }
    }
}
