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
import java.util.Set;
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
    private static final Set<String> NAVIGATION = Set.of("/comm/jobs/search-results", "/comm/jobs/search-results/",
            "/comm/jobs/alerts", "/comm/jobs/alerts/");
    private static final String TITLE = "h1, h2, h3, h4, .job-title, .job-card-title, .job-card__title, [data-job-title]";
    private static final String EMPLOYER = ".job-company, .job-employer, .job-card-company, "
            + ".job-card__company, [data-job-employer], [data-job-company]";
    private static final String LOCATION = ".job-location, .job-card-location, .job-card__location, "
            + "[data-job-location], .job-card-container__metadata-item";
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
                add(link.attr("href"), link, cards, counts);
            }
        } else {
            Matcher links = PLAIN_URL.matcher(body);
            while (links.find()) {
                add(links.group(), null, cards, counts);
            }
        }
    }

    private static void add(String raw, Element link,
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
        if (!supportedHost(uri)) {
            if (host != null && (host.equalsIgnoreCase("linkedin.com")
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".linkedin.com"))
                    && uri.getPath() != null && uri.getPath().contains("/jobs/")) {
                unsupported(counts);
            }
            return;
        }
        String jobId = jobId(uri);
        if (jobId == null) {
            if (uri.getPath() != null && uri.getPath().contains("/jobs/") && !NAVIGATION.contains(uri.getRawPath())) {
                unsupported(counts);
            }
            return;
        }
        if (cards.size() >= MAX_CARDS && !cards.containsKey(jobId)) {
            throw new LinkedInAlertException("TOO_MANY_CARDS");
        }
        CardFields fields = link == null ? new CardFields(null, null, null) : fields(link, jobId);
        String cleanTitle = fields.title();
        if (cleanTitle == null || cleanTitle.isBlank() || cleanTitle.length() > 255
                || cleanTitle.matches("(?i)(?:view|see|apply(?: for)?|open) (?:this )?job")) {
            cleanTitle = "LinkedIn job " + jobId;
        }
        String direction = direction(fields.location());
        cards.merge(jobId, new LinkedInAlertDTO.CardDTO(jobId, cleanTitle,
                        "https://www.linkedin.com/jobs/view/" + jobId + "/", direction, fields.location(), fields.employer()),
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
        String employer = previous.advertisedEmployer() == null ? incoming.advertisedEmployer()
                : previous.advertisedEmployer();
        return new LinkedInAlertDTO.CardDTO(previous.jobId(),
                previous.title().startsWith("LinkedIn job ") ? incoming.title() : previous.title(),
                previous.originalUrl(), direction, evidence, employer);
    }

    private static boolean supportedHost(URI uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("linkedin.com") || host.equalsIgnoreCase("www.linkedin.com")
                || host.equalsIgnoreCase("de.linkedin.com") || host.equalsIgnoreCase("at.linkedin.com"))
                && uri.getUserInfo() == null && uri.getPort() == -1;
    }

    private static String jobId(URI uri) {
        Matcher path = JOB_PATH.matcher(uri.getPath());
        return path.find() ? path.group(1) : queryJobId(uri.getRawQuery());
    }

    private static CardFields fields(Element link, String jobId) {
        String title = field(link, TITLE, 255, "data-job-title");
        if (title == null && link.is("a.font-bold.text-md")) {
            Element row = link.closest("tr");
            if (row != null && row.previousElementSibling() == null) {
                return tableCard(link, row, jobId);
            }
        }
        // The legacy simple link contains only a title. A composite card needs a title element.
        if (title == null && link.childrenSize() == 0) {
            title = link.text();
        }
        String employer = field(link, EMPLOYER, 255, "data-job-employer", "data-job-company");
        String location = field(link, LOCATION, 120, "data-job-location");
        Element parent = link.parent();
        if (location == null && link.select(LOCATION).isEmpty() && parent != null
                && !"body".equals(parent.normalName()) && parent.select("a[href]").size() == 1) {
            location = field(parent, LOCATION, 120, "data-job-location");
        }
        return new CardFields(title, employer, location);
    }

    private static CardFields tableCard(Element titleLink, Element row, String jobId) {
        // Jsoup can separate nested anchors. Start at the identified title and stay in its row group.
        String title = bounded(content(titleLink).text(), 255);
        CardFields titleOnly = new CardFields(title, null, null);
        Element table = row.closest("table");
        Element wrapper = table == null ? null : table.closest("a[href]");
        if (wrapper != null && !linksToJob(wrapper, jobId)) {
            return titleOnly;
        }
        Element metadata = row.nextElementSibling();
        if (metadata == null || !metadata.is("tr") || metadata.childrenSize() != 1
                || !metadata.child(0).is("td") || metadata.selectFirst("table, a[href]") != null) {
            return titleOnly;
        }
        var paragraphs = metadata.child(0).select("p");
        if (paragraphs.size() != 1 || row.select("a[href]").stream().anyMatch(a -> !linksToJob(a, jobId))) {
            return titleOnly;
        }
        Element paragraph = content(paragraphs.first());
        paragraph.select("br").before(" · ");
        String[] parts = paragraph.text().split("\\s+[·•]\\s+", -1);
        return parts.length == 2
                ? new CardFields(title, bounded(parts[0], 255), bounded(parts[1], 120)) : titleOnly;
    }

    private static boolean linksToJob(Element link, String expected) {
        try {
            URI uri = URI.create(link.attr("href"));
            return supportedHost(uri) && expected.equals(jobId(uri));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String field(Element scope, String selector, int limit, String... attributes) {
        var matches = scope.select(selector);
        if (matches.size() != 1) {
            return null;
        }
        Element item = matches.first();
        for (String attribute : attributes) {
            if (item.hasAttr(attribute)) {
                return bounded(item.attr(attribute), limit);
            }
        }
        return bounded(content(item).text(), limit);
    }

    private static Element content(Element item) {
        Element copy = item.clone();
        copy.select("button, [role=button], .social-proof, .job-card-social").remove();
        return copy;
    }

    private static String bounded(String value, int limit) {
        String clean = value.strip();
        return clean.isEmpty() || clean.length() > limit ? null : clean;
    }

    private record CardFields(String title, String employer, String location) { }

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
