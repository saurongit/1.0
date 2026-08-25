package com.example.telegramawgproxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RestartPolicyTest {
    @Test
    public void delayUsesCappedExponentialBackoff() {
        RestartPolicy policy = new RestartPolicy();
        long[] actual = new long[7];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = policy.nextDelayMs();
        }

        assertArrayEquals(new long[]{1000, 2000, 4000, 8000, 16000, 30000, 30000}, actual);
    }

    @Test
    public void resetReturnsDelayToOneSecond() {
        RestartPolicy policy = new RestartPolicy();
        policy.nextDelayMs();
        policy.nextDelayMs();
        policy.reset();

        assertEquals(1000, policy.nextDelayMs());
    }
}
