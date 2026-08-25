package com.example.telegramawgproxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkChangeDetectorTest {
    @Test
    public void initialNetworkDoesNotRestart() {
        NetworkChangeDetector detector = new NetworkChangeDetector();

        assertFalse(detector.onAvailable("wifi"));
        assertFalse(detector.onLinkProperties("wifi", "wlan0|192.168.0.2"));
    }

    @Test
    public void changingDefaultNetworkRestartsOnce() {
        NetworkChangeDetector detector = new NetworkChangeDetector();
        detector.onAvailable("wifi");
        detector.onLinkProperties("wifi", "wlan0|192.168.0.2");

        assertTrue(detector.onAvailable("cellular"));
        assertFalse(detector.onLinkProperties("cellular", "ccmni|10.0.0.2"));
    }

    @Test
    public void changingAddressOnSameNetworkRestarts() {
        NetworkChangeDetector detector = new NetworkChangeDetector();
        detector.onAvailable("cellular");
        detector.onLinkProperties("cellular", "ccmni|10.0.0.2");

        assertTrue(detector.onLinkProperties("cellular", "ccmni|10.0.0.3"));
        assertFalse(detector.onLinkProperties("cellular", "ccmni|10.0.0.3"));
    }

    @Test
    public void repeatedAvailableDoesNotForgetLinkProperties() {
        NetworkChangeDetector detector = new NetworkChangeDetector();
        detector.onAvailable("wifi");
        detector.onLinkProperties("wifi", "wlan0|192.168.0.2");

        assertFalse(detector.onAvailable("wifi"));
        assertTrue(detector.onLinkProperties("wifi", "wlan0|192.168.0.3"));
    }

    @Test
    public void networkReturningAfterLossRestarts() {
        NetworkChangeDetector detector = new NetworkChangeDetector();
        detector.onAvailable("wifi");
        detector.onLost("wifi");

        assertTrue(detector.onAvailable("cellular"));
    }
}
