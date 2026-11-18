package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.LinkedInAlertDTO;
import com.alexs.jobagent.source.exception.LinkedInAlertException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class LinkedInAlertParser {
    private static final Pattern JOB_PATH = Pattern.compile("/(?:comm/)?jobs/view/(\\d{4,32})(?:/|$)");
    private static final Pattern PLAIN_URL = Pattern.compile("https://[^\\s<>\"']{1,2048}");
    private static final int MAX_PARTS = 60;
    private static final int MAX_PART_BYTES = 512_000;
    private static final int MAX_CARDS = 100;
    private static final Pattern SENDER = Pattern.compile("(?:^|<)jobalerts-noreply@linkedin\\.com(?:>|$)",
            Pattern.CASE_INSENSITIVE);
    public LinkedInAlertDTO parse(JsonNode message) {
        if (message == null) {
            throw new LinkedInAlertException("INVALID_MESSAGE");
        }
        String id = text(message.path("id"));
        String date = text(message.path("internalDate"));
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}") || date == null) {
            throw new LinkedInAlertException("INVALID_METADATA");
        }
        Instant observed;
        try {
            observed = Instant.ofEpochMilli(Long.parseLong(date));
        } catch (RuntimeException invalid) {
            throw new LinkedInAlertException("INVALID_DATE");
        }
        JsonNode payload = message.path("payload");
        if (!hasLinkedInSender(payload.path("headers"))) {
            throw new LinkedInAlertException("UNSUPPORTED_SENDER");
        }
        Map<String, LinkedInAlertDTO.CardDTO> cards = new LinkedHashMap<>();
        int[] counts = new int[2];
        visit(payload, cards, counts);
        return new LinkedInAlertDTO(id, observed, List.copyOf(cards.values()), counts[1]);
    }

    private static boolean hasLinkedInSender(JsonNode headers) {
        if (!headers.isArray()) {
            return false;
        }
        for (JsonNode header : headers) {
            if ("from".equalsIgnoreCase(text(header.path("name")))) {
                String value = text(header.path("value"));
                return value != null && SENDER.matcher(value.strip()).find();
            }
        }
        return false;
    }

    private void visit(JsonNode part, Map<String, LinkedInAlertDTO.CardDTO> cards, int[] counts) {
        if (++counts[0] > MAX_PARTS) {
            throw new LinkedInAlertException("TOO_MANY_PARTS");
        }
        JsonNode children = part.path("parts");
        if (children.isArray()) {
            for (JsonNode child : children) {
                visit(child, cards, counts);
            }
        }
        String mime = text(part.path("mimeType"));
        String encoded = text(part.path("body").path("data"));
        if (!("text/html".equalsIgnoreCase(mime) || "text/plain".equalsIgnoreCase(mime))) {
            return;
        }
        if (encoded == null) {
            unsupported(counts);
            return;
        }
        if (encoded.length() > MAX_PART_BYTES * 2) {
            throw new LinkedInAlertException("PART_TOO_LARGE");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new LinkedInAlertException("INVALID_ENCODING");
        }
        if (bytes.length > MAX_PART_BYTES) {
            throw new LinkedInAlertException("PART_TOO_LARGE");
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if ("text/html".equalsIgnoreCase(mime)) {
            for (Element link : Jsoup.parse(body).select("a[href]")) {
                add(link.attr("href"), link.text(), location(link), cards, counts);
            }
        } else {
            Matcher links = PLAIN_URL.matcher(body);
            while (links.find()) {
                add(links.group(), null, null, cards, counts);
            }
        }
    }

    private static void add(String raw, String title, String location,
                            Map<String, LinkedInAlertDTO.CardDTO> cards, int[] counts) {
        URI uri;
        try {
            uri = URI.create(raw.strip().replaceAll("[.,;)]{1,6}$", "").replace("&amp;", "&"));
        } catch (IllegalArgumentException invalid) {
            unsupported(counts);
            return;
        }
        String host = uri.getHost();
        if (host != null && host.equalsIgnoreCase("lnkd.in")) {
            unsupported(counts);
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("linkedin.com") || host.equalsIgnoreCase("www.linkedin.com")
                || host.equalsIgnoreCase("de.linkedin.com") || host.equalsIgnoreCase("at.linkedin.com"))
                || uri.getUserInfo() != null) {
            if (host != null && (host.equalsIgnoreCase("linkedin.com")
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".linkedin.com"))
                    && uri.getPath() != null && uri.getPath().contains("/jobs/")) {
                unsupported(counts);
            }
            return;
        }
        Matcher path = JOB_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        String jobId = path.find() ? path.group(1) : queryJobId(uri.getRawQuery());
        if (jobId == null) {
            if (uri.getPath() != null && uri.getPath().contains("/jobs/")) {
                unsupported(counts);
            }
            return;
        }
        if (cards.size() >= MAX_CARDS && !cards.containsKey(jobId)) {
            throw new LinkedInAlertException("TOO_MANY_CARDS");
        }
        String cleanTitle = title == null ? null : Jsoup.parse(title).text().strip();
        if (cleanTitle == null || cleanTitle.isBlank() || cleanTitle.length() > 255
                || cleanTitle.matches("(?i)(?:view|see|apply(?: for)?|open) (?:this )?job")) {
            cleanTitle = "LinkedIn job " + jobId;
        }
        String direction = direction(location);
        cards.merge(jobId, new LinkedInAlertDTO.CardDTO(jobId, cleanTitle,
                        "https://www.linkedin.com/jobs/view/" + jobId + "/", direction, location),
                LinkedInAlertParser::merge);
    }

    private static LinkedInAlertDTO.CardDTO merge(LinkedInAlertDTO.CardDTO previous,
                                                    LinkedInAlertDTO.CardDTO incoming) {
        String direction = "UNKNOWN".equals(previous.advertisedDirection()) ? incoming.advertisedDirection()
                : "UNKNOWN".equals(incoming.advertisedDirection())
                || previous.advertisedDirection().equals(incoming.advertisedDirection())
                ? previous.advertisedDirection() : "UNKNOWN";
        boolean conflict = !"UNKNOWN".equals(previous.advertisedDirection())
                && !"UNKNOWN".equals(incoming.advertisedDirection())
                && !previous.advertisedDirection().equals(incoming.advertisedDirection());
        String evidence = conflict ? null
                : direction.equals(previous.advertisedDirection())
                ? previous.directionEvidence() == null ? incoming.directionEvidence() : previous.directionEvidence()
                : incoming.directionEvidence();
        return new LinkedInAlertDTO.CardDTO(previous.jobId(),
                previous.title().startsWith("LinkedIn job ") ? incoming.title() : previous.title(),
                previous.originalUrl(), direction, evidence);
    }

    private static String location(Element link) {
        Element parent = link.parent();
        if (parent == null || "body".equals(parent.normalName()) || parent.select("a[href]").size() != 1) {
            return null;
        }
        var locations = parent.select(".job-location, [data-job-location], .job-card-container__metadata-item");
        if (locations.size() != 1) {
            return null;
        }
        Element location = locations.first();
        String value = location.hasAttr("data-job-location")
                ? location.attr("data-job-location") : location.text();
        value = value.strip();
        return value.isEmpty() || value.length() > 120 ? null : value;
    }

    private static String direction(String evidence) {
        if (evidence == null) {
            return "UNKNOWN";
        }
        String normalized = evidence.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.matches("(?:remote[- ]*)?(?:worldwide|anywhere|global)")) {
            return "REMOTE_WORLDWIDE";
        }
        if (normalized.matches("remote[- ]*europe")) {
            return "REMOTE_EUROPE";
        }
        if (normalized.matches("(?:berlin|munich|münchen|hamburg|germany|deutschland)(?:,? germany)?")) {
            return "GERMANY";
        }
        if (normalized.matches("(?:vienna|wien|graz|austria|österreich)(?:,? austria)?")) {
            return "AUSTRIA";
        }
        return "UNKNOWN";
    }

    private static String queryJobId(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        for (String parameter : rawQuery.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (pair.length == 2 && ("currentJobId".equals(pair[0]) || "jobId".equals(pair[0]))) {
                try {
                    String id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (id.matches("\\d{4,32}")) {
                        return id;
                    }
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void unsupported(int[] counts) {
        if (++counts[1] > MAX_CARDS) {
            throw new LinkedInAlertException("TOO_MANY_UNSUPPORTED_LINKS");
        }
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : null;
    }
}
