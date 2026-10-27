package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AtsSourceIdentityParser {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private AtsSourceIdentityParser() { }

    public static Optional<AtsSourceIdentityDTO> parse(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
            return Optional.empty();
        }
        String[] segments = url.getPath().split("/");
        if (segments.length < 2 || !ID.matcher(segments[1]).matches()
                || "embed".equals(segments[1])) {
            return Optional.empty();
        }
        return switch (url.getHost().toLowerCase(java.util.Locale.ROOT)) {
            case "boards.greenhouse.io", "job-boards.greenhouse.io" ->
                    Optional.of(new AtsSourceIdentityDTO("GREENHOUSE", "global", segments[1]));
            case "jobs.lever.co" -> Optional.of(new AtsSourceIdentityDTO("LEVER", "global", segments[1]));
            case "jobs.eu.lever.co" -> Optional.of(new AtsSourceIdentityDTO("LEVER", "eu", segments[1]));
            default -> Optional.empty();
        };
    }
}
