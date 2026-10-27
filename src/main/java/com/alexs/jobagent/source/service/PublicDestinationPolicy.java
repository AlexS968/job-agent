package com.alexs.jobagent.source.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.DnsResolver;

/** Validates every address returned to the connection manager, not a separate preflight lookup. */
public final class PublicDestinationPolicy implements DnsResolver {
    private static final Pattern HOST = Pattern.compile("(?i)(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
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
                    || !HOST.matcher(host).matches() || uri.getRawUserInfo() != null
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
        if (!HOST.matcher(host).matches()) {
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

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
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
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            return (first & 0xe0) == 0x20
                    && !(first == 0x20 && second == 0x02) // 6to4 embeds an IPv4 destination.
                    && !(first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 0) // Teredo.
                    && !(first == 0x20 && second == 0x01 && (bytes[2] & 255) == 0x0d
                    && (bytes[3] & 255) == 0xb8);
        }
        return false;
    }

    @FunctionalInterface
    public interface HostLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
