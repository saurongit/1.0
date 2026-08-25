package com.example.telegramawgproxy;

final class NetworkRestartGate {
    private final long settleWindowMs;
    private long lastDefaultNetworkChangeMs = Long.MIN_VALUE;

    NetworkRestartGate(long settleWindowMs) {
        this.settleWindowMs = settleWindowMs;
    }

    synchronized void onDefaultNetworkChanged(long nowMs) {
        lastDefaultNetworkChangeMs = nowMs;
    }

    synchronized boolean shouldRestartForLinkChange(long nowMs) {
        if (lastDefaultNetworkChangeMs == Long.MIN_VALUE) {
            return true;
        }
        return nowMs - lastDefaultNetworkChangeMs >= settleWindowMs;
    }
}
