package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class AtsSourceIdentityParser {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final Set<String> GREENHOUSE = Set.of("boards.greenhouse.io", "job-boards.greenhouse.io",
            "boards.eu.greenhouse.io", "job-boards.eu.greenhouse.io");
    private static final Set<String> EMBEDS = Set.of("/embed/job_board", "/embed/job_board/js", "/embed/job_app");

    private AtsSourceIdentityParser() { }

    public static Optional<AtsSourceIdentityDTO> parse(URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
            return Optional.empty();
        }
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (isGreenhouse(host) && EMBEDS.contains(url.getRawPath())) {
            return embeddedBoard(url.getRawQuery());
        }
        String[] segments = url.getPath().split("/");
        if (segments.length < 2 || !ID.matcher(segments[1]).matches()
                || "embed".equals(segments[1])) {
            return Optional.empty();
        }
        if (isGreenhouse(host)) {
            return Optional.of(new AtsSourceIdentityDTO("GREENHOUSE", "global", segments[1]));
        }
        return switch (host) {
            case "jobs.lever.co" -> Optional.of(new AtsSourceIdentityDTO("LEVER", "global", segments[1]));
            case "jobs.eu.lever.co" -> Optional.of(new AtsSourceIdentityDTO("LEVER", "eu", segments[1]));
            default -> Optional.empty();
        };
    }

    private static Optional<AtsSourceIdentityDTO> embeddedBoard(String query) {
        String board = null;
        for (String parameter : (query == null ? "" : query).split("&")) {
            if (parameter.startsWith("for=")) {
                String token = parameter.substring(4);
                if (board != null || !ID.matcher(token).matches()) {
                    return Optional.empty();
                }
                board = token;
            }
        }
        return Optional.ofNullable(board).map(id -> new AtsSourceIdentityDTO("GREENHOUSE", "global", id));
    }

    static boolean isGreenhouse(String host) {
        return GREENHOUSE.contains(host);
    }
}
