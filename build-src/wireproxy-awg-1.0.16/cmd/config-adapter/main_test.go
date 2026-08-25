package main

import (
	"strings"
	"testing"
)

func TestWithLocalSocksAddsExpectedEndpoint(t *testing.T) {
	prepared := withLocalSocks("[Interface]\r\nPrivateKey = secret\r\n[Peer]\r\nEndpoint = 1.1.1.1:500\r\n")
	if strings.Count(prepared, "[Socks5]") != 1 {
		t.Fatalf("expected one Socks5 section, got %q", prepared)
	}
	if !strings.Contains(prepared, "BindAddress = 127.0.0.1:1080") {
		t.Fatalf("local endpoint is missing: %q", prepared)
	}
}

func TestWithLocalSocksReplacesExistingSection(t *testing.T) {
	raw := "[Interface]\nPrivateKey = secret\n[Socks5]\nBindAddress = 0.0.0.0:9999\nUsername = leaked\n[Peer]\nEndpoint = 1.1.1.1:500\n"
	prepared := withLocalSocks(raw)
	if strings.Contains(prepared, "0.0.0.0:9999") || strings.Contains(prepared, "Username") {
		t.Fatalf("old Socks5 options survived: %q", prepared)
	}
	if !strings.Contains(prepared, "[Peer]\nEndpoint = 1.1.1.1:500") {
		t.Fatalf("section after Socks5 was removed: %q", prepared)
	}
}
