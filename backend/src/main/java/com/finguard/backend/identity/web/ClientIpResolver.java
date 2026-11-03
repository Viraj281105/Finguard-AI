package com.finguard.backend.identity.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Works out the client address used by the authentication rate limiter.
 *
 * <p>{@code X-Forwarded-For} is only believed when the TCP peer is a configured trusted proxy, and then it is read from
 * the <b>right</b>: every proxy appends the address it received the request from, so the right-most entries were written
 * by infrastructure we trust and the first address that is not a trusted proxy is the real client. Reading from the left
 * (the previous behaviour) returns whatever the client itself put at the start of the header, which lets an attacker
 * rotate fake addresses to dodge the per-IP limits.
 *
 * <p>Trusted proxies are exact addresses or CIDR ranges ({@code 172.16.0.0/12}, {@code fd00::/8}); container networks
 * hand out addresses dynamically, so exact addresses alone cannot describe a reverse proxy running next to the backend.
 */
public final class ClientIpResolver {

    public static final String DEFAULT_TRUSTED_PROXIES = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final int MAX_KEY_LENGTH = 64;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private ClientIpResolver() {
    }

    public static String resolve(String remoteAddr, String xForwardedFor, String trustedProxiesConfig) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        String remote = remoteAddr.trim();
        List<Matcher> trusted = parse(trustedProxiesConfig);
        if (xForwardedFor == null || xForwardedFor.isBlank() || !isTrusted(remote, trusted)) {
            return cap(remote);
        }
        String[] hops = xForwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrusted(hop, trusted)) {
                return cap(hop);
            }
        }
        // Every hop is one of our own proxies: the left-most entry is the original client as seen by the first of them.
        for (String hop : hops) {
            if (!hop.isBlank()) {
                return cap(hop.trim());
            }
        }
        return cap(remote);
    }

    private static String cap(String value) {
        return value.length() > MAX_KEY_LENGTH ? value.substring(0, MAX_KEY_LENGTH) : value;
    }

    // ---- trusted proxy matching ---------------------------------------------------------------------------------

    private record Matcher(byte[] network, int prefixBits) {
    }

    private static List<Matcher> parse(String config) {
        String effective = (config == null || config.isBlank()) ? DEFAULT_TRUSTED_PROXIES : config;
        List<Matcher> matchers = new ArrayList<>();
        for (String entry : effective.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int slash = trimmed.indexOf('/');
            String address = slash < 0 ? trimmed : trimmed.substring(0, slash);
            byte[] bytes = literal(address);
            if (bytes == null) {
                throw new IllegalStateException("app.trusted-proxies contains an entry that is not an IP address or CIDR: " + trimmed);
            }
            int bits = bytes.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(trimmed.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("app.trusted-proxies has an invalid prefix length: " + trimmed, e);
                }
                if (bits < 0 || bits > bytes.length * 8) {
                    throw new IllegalStateException("app.trusted-proxies has an out-of-range prefix length: " + trimmed);
                }
            }
            matchers.add(new Matcher(bytes, bits));
        }
        return matchers;
    }

    private static boolean isTrusted(String candidate, List<Matcher> trusted) {
        byte[] address = literal(candidate);
        if (address == null) {
            return false;
        }
        for (Matcher matcher : trusted) {
            if (matcher.network().length == address.length && matchesPrefix(matcher.network(), address, matcher.prefixBits())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrefix(byte[] network, byte[] address, int bits) {
        int fullBytes = bits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != address[i]) {
                return false;
            }
        }
        int remaining = bits % 8;
        if (remaining == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remaining)) & 0xFF;
        return (network[fullBytes] & mask) == (address[fullBytes] & mask);
    }

    /** Parses an IP literal without ever touching DNS; returns null for anything else (host names, junk). */
    private static byte[] literal(String text) {
        String value = text.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        boolean looksLikeIp = IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+"));
        if (!looksLikeIp) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
