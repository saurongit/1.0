package com.example.telegramawgproxy;

final class NetworkChangeDetector {
    private boolean initialized;
    private String activeNetwork;
    private String linkFingerprint;

    synchronized boolean onAvailable(String networkId) {
        String value = valueOrEmpty(networkId);
        boolean sameNetwork = initialized && value.equals(activeNetwork);
        boolean restart = initialized && !sameNetwork;
        if (!sameNetwork) {
            linkFingerprint = null;
        }
        initialized = true;
        activeNetwork = value;
        return restart;
    }

    synchronized boolean onLinkProperties(String networkId, String fingerprint) {
        String networkValue = valueOrEmpty(networkId);
        String linkValue = valueOrEmpty(fingerprint);
        if (!initialized) {
            initialized = true;
            activeNetwork = networkValue;
            linkFingerprint = linkValue;
            return false;
        }
        if (!networkValue.equals(activeNetwork)) {
            activeNetwork = networkValue;
            linkFingerprint = linkValue;
            return true;
        }
        if (linkFingerprint == null) {
            linkFingerprint = linkValue;
            return false;
        }
        boolean changed = !linkValue.equals(linkFingerprint);
        linkFingerprint = linkValue;
        return changed;
    }

    synchronized void onLost(String networkId) {
        if (valueOrEmpty(networkId).equals(activeNetwork)) {
            activeNetwork = null;
            linkFingerprint = null;
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
