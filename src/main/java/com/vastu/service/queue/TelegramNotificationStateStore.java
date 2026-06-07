package com.vastu.service.queue;


import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vastu.enums.NotificationStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe in-memory registry for all Telegram notification records.
 *
 * Replaces the {@code TelegramNotificationRepository} JPA interface with a
 * {@link ConcurrentHashMap} keyed by auto-incremented notification IDs.
 *
 * Responsibilities:
 *  • Assign unique IDs to new notifications.
 *  • Store and retrieve {@link NotificationRecord}s by ID.
 *  • Provide query-like helpers used by the dispatch queue and health endpoint.
 *  • Periodically evict old SENT records to cap memory usage.
 *
 * Durability note:
 *  State lives only in the JVM heap. If the process crashes, in-flight and
 *  pending notifications are lost. This is an intentional trade-off when no
 *  database is available. The only mitigation is to keep the JVM healthy and
 *  use the {@code @PreDestroy} drain in {@link TelegramDispatchQueue}.
 */
@Slf4j
@Component
public class TelegramNotificationStateStore {

    /** Maximum number of SENT records to retain before eviction (memory cap). */
    @Value("${telegram.store.max-sent-records:10000}")
    private int maxSentRecords;

    /** SENT records older than this many hours are eligible for eviction. */
    @Value("${telegram.store.sent-ttl-hours:24}")
    private long sentTtlHours;

    private final ConcurrentHashMap<Long, NotificationRecord> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    // ── Write API ───────────────────────────────────────────────

    /**
     * Create and register a new PENDING notification record.
     *
     * @return the saved record (with assigned ID)
     */
    public NotificationRecord save(String messageText, List<Path> attachments) {
        long id = idSequence.getAndIncrement();
        NotificationRecord record = NotificationRecord.builder()
                .id(id)
                .messageText(messageText)
                .attachments(attachments != null ? attachments : List.of())
                .build();
        store.put(id, record);
        log.debug("[StateStore] Registered notification id={}", id);
        return record;
    }

    // ── Query API ───────────────────────────────────────────────

    public Optional<NotificationRecord> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    /** All records in any of the given statuses, ordered by creation time. */
    public List<NotificationRecord> findByStatuses(NotificationStatus... statuses) {
        var statusSet = java.util.Set.of(statuses);
        return store.values().stream()
                .filter(r -> statusSet.contains(r.getStatus()))
                .sorted(java.util.Comparator.comparing(NotificationRecord::getCreatedAt))
                .collect(Collectors.toList());
    }

    public long countByStatus(NotificationStatus status) {
        return store.values().stream().filter(r -> r.getStatus() == status).count();
    }

    public int totalSize() {
        return store.size();
    }

    public Collection<NotificationRecord> all() {
        return store.values();
    }

    // ── Eviction ────────────────────────────────────────────────

    /**
     * Remove SENT records older than {@code sentTtlHours} hours, or if the SENT
     * count exceeds {@code maxSentRecords}. Called periodically by the queue.
     */
    public void evictOldSentRecords() {
        Instant cutoff = Instant.now().minus(sentTtlHours, ChronoUnit.HOURS);
        List<Long> toRemove = store.values().stream()
                .filter(r -> r.getStatus() == NotificationStatus.SENT)
                .filter(r -> r.getSentAt() != null && r.getSentAt().isBefore(cutoff))
                .map(NotificationRecord::getId)
                .collect(Collectors.toList());

        toRemove.forEach(store::remove);
        if (!toRemove.isEmpty()) {
            log.debug("[StateStore] Evicted {} old SENT records", toRemove.size());
        }

        // Hard cap: if still too many SENT records, remove the oldest ones
        List<NotificationRecord> allSent = store.values().stream()
                .filter(r -> r.getStatus() == NotificationStatus.SENT)
                .sorted(java.util.Comparator.comparing(r ->
                        r.getSentAt() != null ? r.getSentAt() : Instant.EPOCH))
                .collect(Collectors.toList());

        if (allSent.size() > maxSentRecords) {
            int excess = allSent.size() - maxSentRecords;
            allSent.stream().limit(excess).forEach(r -> store.remove(r.getId()));
            log.info("[StateStore] Hard-cap eviction: removed {} oldest SENT records", excess);
        }
    }
}
