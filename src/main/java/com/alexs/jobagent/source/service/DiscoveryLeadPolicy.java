package com.alexs.jobagent.source.service;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Ordering hints only; neither a search URL nor this classification proves employer ownership. */
public final class DiscoveryLeadPolicy {
    private static final Set<String> AGGREGATORS = Set.of("linkedin.com", "indeed.com",
            "glassdoor.com", "wellfound.com", "remotefront.com", "devjobs.de", "devjobs.at");
    private static final Set<String> VENDOR_TOOLS = Set.of("greenhouse.com", "tavily.com");
    private static final Set<String> ATS_HOSTS = Set.of("boards.greenhouse.io", "job-boards.greenhouse.io",
            "jobs.lever.co", "jobs.eu.lever.co");

    private DiscoveryLeadPolicy() { }

    public static Kind classify(String rawUrl) {
        try {
            URI url = PublicDestinationPolicy.requirePublicHttpsUri(rawUrl);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (AtsSourceIdentityParser.parse(url).isPresent()) {
                return Kind.ATS;
            }
            if (isVendorTool(host)) {
                return Kind.NON_EMPLOYER;
            }
            if (ATS_HOSTS.contains(host)) {
                return Kind.NON_EMPLOYER;
            }
            if (isAggregator(host)) {
                return Kind.AGGREGATOR;
            }
            return Kind.EMPLOYER;
        } catch (IllegalArgumentException invalid) {
            return Kind.UNSAFE;
        }
    }

    public static boolean isAggregator(String host) {
        return matchesHostOrSubdomain(host, AGGREGATORS);
    }

    public static boolean isVendorTool(String host) {
        return matchesHostOrSubdomain(host, VENDOR_TOOLS);
    }

    public static boolean isExcludedEmployerHost(String host) {
        return isAggregator(host) || isVendorTool(host);
    }

    private static boolean matchesHostOrSubdomain(String host, Set<String> domains) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return domains.stream().anyMatch(domain -> normalized.equals(domain)
                || normalized.endsWith("." + domain));
    }

    public enum Kind {
        ATS(0), EMPLOYER(1), AGGREGATOR(2), NON_EMPLOYER(2), UNSAFE(3);

        private final int priority;

        Kind(int priority) { this.priority = priority; }

        public int priority() { return priority; }
    }
}
