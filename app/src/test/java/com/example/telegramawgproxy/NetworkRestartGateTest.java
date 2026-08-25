package com.example.telegramawgproxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkRestartGateTest {
    @Test
    public void linkChangeBeforeAnyFailoverRestarts() {
        NetworkRestartGate gate = new NetworkRestartGate(10000);

        assertTrue(gate.shouldRestartForLinkChange(1000));
    }

    @Test
    public void latePropertiesFromFailoverAreCoalesced() {
        NetworkRestartGate gate = new NetworkRestartGate(10000);
        gate.onDefaultNetworkChanged(1000);

        assertFalse(gate.shouldRestartForLinkChange(5000));
        assertTrue(gate.shouldRestartForLinkChange(11000));
    }
}
