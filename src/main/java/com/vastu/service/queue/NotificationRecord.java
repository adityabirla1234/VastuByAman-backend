package com.vastu.service.queue;


import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.vastu.enums.NotificationStatus;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Pure in-memory record representing a single Telegram notification.
 *
 * This replaces the JPA {@code TelegramNotification} entity entirely.
 * All state transitions are lock-free via {@link AtomicReference} and
 * {@link AtomicInteger}, making it safe to share across worker threads
 * without synchronization overhead.
 *
 * Lifecycle:
 *   PENDING → SENDING  (worker claims it via CAS)
 *   SENDING → SENT     (delivery succeeded)
 *   SENDING → PENDING  (delivery failed, will retry)
 *   PENDING → FAILED   (exhausted all retries)
 */
@Getter
@ToString
@Builder
public class NotificationRecord {

    /** Unique ID — assigned by {@link TelegramNotificationStateStore}. */
    private final long id;

    /** Pre-formatted Telegram MarkdownV2 message. */
    private final String messageText;

    /** Optional file attachments to send as documents after the text. */
    @Builder.Default
    private final List<Path> attachments = List.of();

    /** When this record was created (for ordering / TTL). */
    @Builder.Default
    private final Instant createdAt = Instant.now();

    // ── Mutable state (atomic, no locking required) ─────────────

    /** Current delivery status. */
    @Builder.Default
    private final AtomicReference<NotificationStatus> status =
            new AtomicReference<>(NotificationStatus.PENDING);

    /** How many delivery attempts have been made so far. */
    @Builder.Default
    private final AtomicInteger attemptCount = new AtomicInteger(0);

    /** Last error message (informational; set on failure). */
    @Builder.Default
    private volatile String lastError = null;

    /** Timestamp of the last delivery attempt. */
    @Builder.Default
    private volatile Instant lastAttemptedAt = null;

    /** Timestamp of successful delivery. */
    @Builder.Default
    private volatile Instant sentAt = null;

    // ── State transition helpers ────────────────────────────────

    /**
     * Atomically transition from {@code expected} → {@code next}.
     *
     * @return {@code true} if the CAS succeeded (this thread owns the transition),
     *         {@code false} if another thread already changed the status.
     */
    public boolean compareAndSetStatus(NotificationStatus expected, NotificationStatus next) {
        return status.compareAndSet(expected, next);
    }

    public NotificationStatus getStatus() {
        return status.get();
    }

    public int getAttemptCount() {
        return attemptCount.get();
    }

    public int incrementAndGetAttemptCount() {
        return attemptCount.incrementAndGet();
    }

    public void markSent() {
        sentAt = Instant.now();
        status.set(NotificationStatus.SENT);
    }

    public void markFailed(String error) {
        lastError = error;
        lastAttemptedAt = Instant.now();
        status.set(NotificationStatus.FAILED);
    }

    public void resetToPending(String error) {
        lastError = error;
        lastAttemptedAt = Instant.now();
        status.set(NotificationStatus.PENDING);
    }
}
