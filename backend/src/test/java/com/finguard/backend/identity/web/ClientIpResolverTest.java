package com.finguard.backend.identity.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static final String DOCKER = "127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16";

    @Test
    void anUntrustedPeerCannotInjectForwardedFor() {
        assertThat(ClientIpResolver.resolve("198.51.100.77", "1.2.3.4, 5.6.7.8", null)).isEqualTo("198.51.100.77");
    }

    @Test
    void theClientCannotChooseItsOwnAddressThroughTheLeftOfTheHeader() {
        // The client sent "X-Forwarded-For: 9.9.9.9"; the reverse proxy (172.18.0.5, a trusted container address) appended
        // the address it actually saw. The left-most value is attacker-controlled and must not be used.
        assertThat(ClientIpResolver.resolve("172.18.0.5", "9.9.9.9, 203.0.113.5", DOCKER)).isEqualTo("203.0.113.5");
    }

    @Test
    void rotatingFakeLeftHandAddressesYieldsTheSameKey() {
        String a = ClientIpResolver.resolve("172.18.0.5", "1.1.1.1, 203.0.113.5", DOCKER);
        String b = ClientIpResolver.resolve("172.18.0.5", "2.2.2.2, 203.0.113.5", DOCKER);
        assertThat(a).isEqualTo(b).isEqualTo("203.0.113.5");
    }

    @Test
    void trustedProxiesInTheChainAreSkippedFromTheRight() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "203.0.113.195, 10.0.0.1", "127.0.0.1,10.0.0.1"))
                .isEqualTo("203.0.113.195");
    }

    @Test
    void anAddressInsideAConfiguredCidrIsTrustedAndOutsideIsNot() {
        assertThat(ClientIpResolver.resolve("172.31.255.254", "203.0.113.9", "172.16.0.0/12")).isEqualTo("203.0.113.9");
        assertThat(ClientIpResolver.resolve("172.32.0.1", "203.0.113.9", "172.16.0.0/12")).isEqualTo("172.32.0.1");
    }

    @Test
    void ipv6PeersAndRangesWork() {
        assertThat(ClientIpResolver.resolve("fd00::5", "2001:db8::1", "fd00::/8")).isEqualTo("2001:db8::1");
        assertThat(ClientIpResolver.resolve("2001:db8::99", "2001:db8::1", "fd00::/8")).isEqualTo("2001:db8::99");
    }

    @Test
    void defaultsTrustOnlyLoopback() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "203.0.113.7", "")).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.resolve("10.1.2.3", "203.0.113.7", "")).isEqualTo("10.1.2.3");
    }

    @Test
    void junkHeaderEntriesAreBoundedAndNeverResolvedAsHostNames() {
        String junk = "x".repeat(500);
        assertThat(ClientIpResolver.resolve("127.0.0.1", junk, null)).hasSize(64);
        assertThat(ClientIpResolver.resolve("127.0.0.1", "evil.example.com", null)).isEqualTo("evil.example.com");
    }

    @Test
    void whenEveryHopIsTrustedTheLeftMostIsTheClient() {
        assertThat(ClientIpResolver.resolve("127.0.0.1", "10.0.0.7, 10.0.0.8", "127.0.0.1,10.0.0.0/8")).isEqualTo("10.0.0.7");
    }

    @Test
    void missingPeerAndNoHeader() {
        assertThat(ClientIpResolver.resolve(null, "1.2.3.4", null)).isEqualTo("unknown");
        assertThat(ClientIpResolver.resolve("127.0.0.1", null, null)).isEqualTo("127.0.0.1");
    }

    @Test
    void aMisconfiguredTrustListFailsLoudly() {
        assertThatThrownBy(() -> ClientIpResolver.resolve("127.0.0.1", "1.2.3.4", "not-an-ip"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not-an-ip");
        assertThatThrownBy(() -> ClientIpResolver.resolve("127.0.0.1", "1.2.3.4", "10.0.0.0/99"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prefix");
    }
}
