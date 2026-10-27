package com.alexs.jobagent.source.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;

/** Validates every address returned to the connection manager, not a separate preflight lookup. */
public final class PublicDestinationPolicy implements DnsResolver {
    private final HostLookup lookup;

    public PublicDestinationPolicy() {
        this(InetAddress::getAllByName);
    }

    public PublicDestinationPolicy(HostLookup lookup) {
        this.lookup = lookup;
    }

    public static URI requirePublicHttpsUri(String text) {
        try {
            URI uri = URI.create(text);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !validHostname(host) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawFragment() != null || (uri.getRawPath() != null && uri.getRawPath().length() > 2048)
                    || (uri.getRawQuery() != null && uri.getRawQuery().length() > 1024)) {
                throw new IllegalArgumentException("Unsafe verification URL");
            }
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unsafe verification URL", invalid);
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!validHostname(host)) {
            throw new UnknownHostException("Unsafe verification host");
        }
        InetAddress[] addresses = lookup.resolve(host);
        if (Thread.currentThread().isInterrupted() || addresses.length == 0) {
            throw new UnknownHostException("Verification DNS unavailable");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnknownHostException("Verification destination is not public");
            }
        }
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean validHostname(String host) {
        if (host == null || host.length() < 4 || host.length() > 253) {
            return false;
        }
        int start = 0;
        int dot = host.indexOf('.');
        if (dot < 0) {
            return false;
        }
        while (dot >= 0) {
            if (!validLabel(host, start, dot, false)) {
                return false;
            }
            start = dot + 1;
            dot = host.indexOf('.', start);
        }
        return validLabel(host, start, host.length(), true);
    }

    private static boolean validLabel(String host, int start, int end, boolean topLevel) {
        int length = end - start;
        if (length < (topLevel ? 2 : 1) || length > 63
                || !asciiLetterOrDigit(host.charAt(start)) || !asciiLetterOrDigit(host.charAt(end - 1))) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char value = host.charAt(i);
            if (topLevel ? !asciiLetter(value) : value != '-' && !asciiLetterOrDigit(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static boolean asciiLetterOrDigit(char value) {
        return asciiLetter(value) || value >= '0' && value <= '9';
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return publicIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return publicIpv6(address.getAddress());
        }
        return false;
    }

    private static boolean publicIpv4(byte[] bytes) {
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        int c = bytes[2] & 255;
        return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 0 || b == 168 || (b == 88 && c == 99)))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }

    private static boolean publicIpv6(byte[] bytes) {
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x02) // 6to4 embeds an IPv4 destination.
                && !(first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) // Teredo.
                && !(first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0d
                && (bytes[3] & 255) == 0xb8);
    }

    @FunctionalInterface
    public interface HostLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
