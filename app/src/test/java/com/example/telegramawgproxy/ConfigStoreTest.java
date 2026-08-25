package com.example.telegramawgproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConfigStoreTest {
    @Test
    public void withLocalSocksAddsOneLocalEndpoint() {
        String source = "[Interface]\r\nPrivateKey = secret\r\n[Peer]\r\nEndpoint = 1.1.1.1:500\r\n";
        String prepared = ConfigStore.withLocalSocks(source);

        assertEquals(1, occurrences(prepared, "[Socks5]"));
        assertTrue(prepared.contains("BindAddress = 127.0.0.1:1080"));
        assertFalse(prepared.contains("\r"));
        assertEquals(
                source.replace("\r\n", "\n")
                        + "\n\n[Socks5]\nBindAddress = 127.0.0.1:1080\n",
                prepared
        );
    }

    @Test
    public void withLocalSocksReplacesUnsafeExistingSection() {
        String prepared = ConfigStore.withLocalSocks(
                "[Interface]\nPrivateKey = secret\n"
                        + "[Socks5]\nBindAddress = 0.0.0.0:9999\nUsername = old\nPassword = old\n"
                        + "[Peer]\nEndpoint = 1.1.1.1:500\n"
        );

        assertEquals(1, occurrences(prepared, "[Socks5]"));
        assertFalse(prepared.contains("0.0.0.0:9999"));
        assertFalse(prepared.contains("Username = old"));
        assertFalse(prepared.contains("Password = old"));
        assertTrue(prepared.contains("[Peer]\nEndpoint = 1.1.1.1:500"));
    }

    @Test
    public void withLocalSocksIsIdempotent() {
        String once = ConfigStore.withLocalSocks("[Interface]\nPrivateKey = secret\n");
        String twice = ConfigStore.withLocalSocks(once);

        assertEquals(1, occurrences(twice, "[Socks5]"));
        assertEquals(1, occurrences(twice, "BindAddress = 127.0.0.1:1080"));
    }

    @Test
    public void withLocalSocksPreservesAwgConnectionFieldsExactly() {
        String source = "[Interface]\n"
                + "PrivateKey = private-key\n"
                + "Jc = 7\nJmin = 50\nJmax = 1000\nS1 = 86\nS2 = 94\n"
                + "H1 = 123456789\nH2 = 987654321\nH3 = 111111111\nH4 = 222222222\n"
                + "[Peer]\n"
                + "PublicKey = public-key\n"
                + "PresharedKey = preshared-key\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "Endpoint = vpn.example.test:443\n"
                + "PersistentKeepalive = 25\n";

        String prepared = ConfigStore.withLocalSocks(source);

        assertTrue(prepared.startsWith(source));
        assertEquals(1, occurrences(prepared, "[Socks5]"));
        assertEquals(1, occurrences(prepared, "BindAddress = 127.0.0.1:1080"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
