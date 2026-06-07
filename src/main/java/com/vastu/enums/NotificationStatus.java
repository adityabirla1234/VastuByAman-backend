package com.vastu.enums;

public enum NotificationStatus {
    PENDING,    // waiting in the queue or scheduled for retry
    SENDING,    // actively being sent by a worker thread right now
    SENT,       // successfully delivered to Telegram
    FAILED      // permanently failed after all retry attempts
}
