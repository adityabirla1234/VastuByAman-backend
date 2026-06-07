package com.vastu.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.vastu.enums.NotificationStatus;
import com.vastu.service.queue.TelegramDispatchQueue;
import com.vastu.service.queue.TelegramNotificationStateStore;

import lombok.RequiredArgsConstructor;

/**
 * Custom Spring Boot Actuator health indicator for the Telegram pipeline.
 *
 * Exposes queue depths, state-store counters, and lifetime delivery stats:
 *   GET /api/actuator/health
 *
 * Status rules:
 *  UP      — no FAILED records
 *  UNKNOWN — at least one permanently failed record (needs operator review)
 *
 * All data comes from the in-memory {@link TelegramNotificationStateStore}
 * and {@link TelegramDispatchQueue} — no database required.
 */
@Component("telegramQueue")
@RequiredArgsConstructor
public class QueueHealthIndicator implements HealthIndicator {

    private final TelegramDispatchQueue  dispatchQueue;
    private final TelegramNotificationStateStore stateStore;

    @Override
    public Health health() {
        long pending  = stateStore.countByStatus(NotificationStatus.PENDING);
        long sending  = stateStore.countByStatus(NotificationStatus.SENDING);
        long sent     = stateStore.countByStatus(NotificationStatus.SENT);
        long failed   = stateStore.countByStatus(NotificationStatus.FAILED);

        int  mainQ     = dispatchQueue.mainQueueSize();
        int  overflowQ = dispatchQueue.overflowQueueSize();
        int  dlq       = dispatchQueue.dlqSize();

        Health.Builder builder = failed > 0 ? Health.unknown() : Health.up();

        return builder
                .withDetail("queue.main",            mainQ)
                .withDetail("queue.overflow",         overflowQ)
                .withDetail("queue.deadLetter",       dlq)
                .withDetail("store.pending",          pending)
                .withDetail("store.sending",          sending)
                .withDetail("store.sent",             sent)
                .withDetail("store.failed",           failed)
                .withDetail("lifetime.totalSent",     dispatchQueue.getTotalSent())
                .withDetail("lifetime.totalFailed",   dispatchQueue.getTotalFailed())
                .withDetail("lifetime.totalRetried",  dispatchQueue.getTotalRetried())
                .withDetail("lifetime.overflowDrops", dispatchQueue.getOverflowDropped())
                .withDetail("status", failed > 0
                        ? "WARNING: " + failed + " notification(s) permanently failed – manual review needed"
                        : "All notifications delivered")
                .build();
    }
}
