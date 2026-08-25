package com.example.telegramawgproxy;

final class RestartPolicy {
    private static final long MIN_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 30000;

    private int failures;

    long nextDelayMs() {
        int shift = Math.min(failures, 5);
        failures++;
        return Math.min(MIN_DELAY_MS << shift, MAX_DELAY_MS);
    }

    void reset() {
        failures = 0;
    }
}
