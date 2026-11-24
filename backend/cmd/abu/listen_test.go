package main

import "testing"

func TestListenAddressDefaultsAndOverrides(t *testing.T) {
	t.Setenv("ABU_LISTEN_ADDR", "0.0.0.0:9000")
	if got, err := listenAddress("192.168.2.10:8081"); err != nil || got != "192.168.2.10:8081" {
		t.Fatalf("flag address got %q err=%v", got, err)
	}
	if got, err := listenAddress(""); err != nil || got != "0.0.0.0:9000" {
		t.Fatalf("environment address got %q err=%v", got, err)
	}
	t.Setenv("ABU_LISTEN_ADDR", "")
	if got, err := listenAddress(""); err != nil || got != "127.0.0.1:8080" {
		t.Fatalf("default address got %q err=%v", got, err)
	}
	if got, err := listenAddress("   "); err != nil || got != "127.0.0.1:8080" {
		t.Fatalf("blank flag got %q err=%v", got, err)
	}
	// A blank environment value falls through to the default instead of an empty address.
	t.Setenv("ABU_LISTEN_ADDR", "  ")
	if got, err := listenAddress(" 127.0.0.1:9001 "); err != nil || got != "127.0.0.1:9001" {
		t.Fatalf("trimmed flag got %q err=%v", got, err)
	}
}

func TestListenAddressAcceptsAnyInterfaceAndIPv6(t *testing.T) {
	for _, addr := range []string{":8081", "[::1]:8080", "0.0.0.0:1", "localhost:65535"} {
		if got, err := listenAddress(addr); err != nil || got != addr {
			t.Fatalf("address %q got %q err=%v", addr, got, err)
		}
	}
}

func TestListenAddressRefusesUnusableValues(t *testing.T) {
	t.Setenv("ABU_LISTEN_ADDR", "")
	for _, addr := range []string{
		"8080", "127.0.0.1", "127.0.0.1:", "127.0.0.1:0", "127.0.0.1:65536",
		"127.0.0.1:abc", "::1", "host:port", "127.0.0.1:8080/path", "127.0.0.1:8080 8081",
	} {
		if got, err := listenAddress(addr); err == nil {
			t.Fatalf("address %q was accepted as %q", addr, got)
		}
	}
}
