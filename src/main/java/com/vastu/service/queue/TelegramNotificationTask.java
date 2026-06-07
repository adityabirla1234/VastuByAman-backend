package com.vastu.service.queue;

import lombok.Builder;
import lombok.Data;

/**
 * Lightweight task placed on the {@link TelegramDispatchQueue}.
 *
 * Carries only the notification ID; the worker fetches the full
 * {@link NotificationRecord} from the {@link TelegramNotificationStateStore}.
 * This keeps the queue lightweight and avoids duplicating mutable state.
 */
@Data
@Builder
public class TelegramNotificationTask {

    /** ID of the corresponding {@link NotificationRecord}. */
    private final long notificationId;
}
