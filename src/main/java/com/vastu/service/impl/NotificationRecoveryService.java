package com.vastu.service.impl;



import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.vastu.enums.NotificationStatus;
import com.vastu.service.queue.NotificationRecord;
import com.vastu.service.queue.TelegramDispatchQueue;
import com.vastu.service.queue.TelegramNotificationStateStore;
import com.vastu.service.queue.TelegramNotificationTask;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recovery service for the in-memory Telegram notification state store.
 *
 * Two recovery paths:
 *
 * 1. @PostConstruct (startup recovery)
 *    Any records stuck in SENDING state (the JVM was interrupted mid-send)
 *    are reset to PENDING so they can be retried. All PENDING records are
 *    then re-enqueued.
 *
 *    Note: because state is in-memory only, after a full JVM restart the
 *    state store is empty and there is nothing to recover. This recovery
 *    path is relevant only when the application context restarts within
 *    the same JVM (e.g. Spring context refresh in tests).
 *
 * 2. @Scheduled (periodic rescue, every 5 minutes)
 *    Catches PENDING records that are in the state store but somehow not
 *    in the dispatch queue (e.g. they were dropped when the queue was
 *    temporarily full). Acts as a safety net against any gap between the
 *    state store and the queue.
 *
 * Together with the CAS-based dispatch queue this provides at-least-once
 * delivery within a single JVM lifetime.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRecoveryService {

    private final TelegramNotificationStateStore stateStore;
    private final TelegramDispatchQueue dispatchQueue;

    // ── Startup recovery ─────────────────────────────────────────

    @PostConstruct
    public void recoverOnStartup() {
        // Reset any records that were mid-send when the context last stopped
        List<NotificationRecord> stuckSending =
                stateStore.findByStatuses(NotificationStatus.SENDING);

        for (NotificationRecord record : stuckSending) {
            record.resetToPending("Reset from SENDING on startup");
        }

        if (!stuckSending.isEmpty()) {
            log.warn("[Recovery] Reset {} SENDING record(s) to PENDING on startup",
                    stuckSending.size());
        }

        // Re-enqueue all PENDING records
        enqueueAllPending("startup");
    }

    // ── Periodic rescue (every 5 minutes) ────────────────────────

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void periodicRescue() {
        enqueueAllPending("periodic-rescue");
    }

    // ── Shared logic ──────────────────────────────────────────────

    private void enqueueAllPending(String trigger) {
        List<NotificationRecord> pending =
                stateStore.findByStatuses(NotificationStatus.PENDING);

        if (pending.isEmpty()) {
            log.debug("[Recovery][{}] No pending notifications found", trigger);
            return;
        }

        log.info("[Recovery][{}] Re-enqueuing {} pending notification(s)", trigger, pending.size());

        List<TelegramNotificationTask> tasks = pending.stream()
                .map(r -> TelegramNotificationTask.builder()
                        .notificationId(r.getId())
                        .build())
                .toList();

        dispatchQueue.enqueueAll(tasks);
        log.info("[Recovery][{}] Enqueued {} task(s) for delivery", trigger, tasks.size());
    }
}
