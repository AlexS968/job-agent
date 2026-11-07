package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class BatchReportFormatter {
    static final int MAX_BYTES = 10_000;

    public String format(LocalDate date, LoadedProfileDTO profile, BatchPipelineResultDTO result) {
        StringBuilder text = new StringBuilder();
        text.append("Job Agent batch: ").append(date).append('\n');
        text.append("Outcome: ").append(result.outcome()).append('\n');
        text.append("Profile: ").append(clean(profile.profile().profileId(), 128)).append('/')
                .append(clean(profile.profile().version(), 64)).append("; policy: rules-v1\n");
        text.append("Import: sources=").append(result.imported().sources())
                .append(", created=").append(result.imported().created())
                .append(", updated=").append(result.imported().updated())
                .append(", unchanged=").append(result.imported().unchanged())
                .append(", failures=").append(result.imported().failures()).append('\n');
        text.append("Processing: published=").append(result.published())
                .append(", evaluated=").append(result.evaluated())
                .append(", duplicates=").append(result.duplicates())
                .append(", stale=").append(result.stale())
                .append(", failed=").append(result.failed()).append('\n');
        text.append("Shortlist: suitable=").append(result.shortlist().suitable().size())
                .append(", needs_review=").append(result.shortlist().needsReview().size()).append('\n');
        text.append("Backlog snapshot: outbox ready=").append(result.outbox().ready())
                .append(", future=").append(result.outbox().futureRetries())
                .append(", active=").append(result.outbox().activeClaims())
                .append(", expired=").append(result.outbox().expiredClaims())
                .append(", failed=").append(result.outbox().terminalFailures())
                .append("; SQS visible=").append(result.queue().visible())
                .append(", in_flight=").append(result.queue().inFlight())
                .append(", delayed=").append(result.queue().delayed()).append('\n');
        text.append("Backlog is a snapshot; concurrent workers may change it. Shortlist is capped at 20.\n");
        List<String> entries = new ArrayList<>();
        result.shortlist().suitable().forEach(item -> entries.add(entry("SUITABLE", item)));
        result.shortlist().needsReview().forEach(item -> entries.add(entry("NEEDS_REVIEW", item)));
        if (entries.isEmpty()) {
            text.append("No suitable or needs-review vacancies in this shortlist.\n");
        }
        int included = 0;
        for (String entry : entries) {
            int omitted = entries.size() - included;
            String suffix = "Omitted entries due to message size: " + omitted + "\n";
            if (bytes(text.toString() + entry + suffix) > MAX_BYTES) {
                break;
            }
            text.append(entry);
            included++;
        }
        text.append("Omitted entries due to message size: ").append(entries.size() - included).append('\n');
        if (bytes(text.toString()) > MAX_BYTES) {
            throw new IllegalStateException("Batch report exceeds its local size bound");
        }
        return text.toString();
    }

    private static String entry(String category, VacancyEvaluationDTO item) {
        String link = safeLink(item.originalUrl());
        return category + " vacancy " + item.vacancyId() + ": " + clean(item.title(), 180) + "\n"
                + "  " + link + "\n"
                + "  reasons=" + codes(item.reasons()) + "; unknowns=" + codes(item.unknowns()) + "\n";
    }

    private static String codes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().limit(20).map(value -> clean(value, 80)).toList().toString();
    }

    private static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && value.length() <= 1000) {
                return clean(value, 1000);
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // An invalid source link is never rendered as a clickable report URL.
        }
        return "[public link unavailable]";
    }

    private static String clean(String value, int maxCharacters) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return normalized.substring(0, Math.min(maxCharacters, normalized.length()));
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
