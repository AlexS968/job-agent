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

    public String subject(LocalDate date, LoadedProfileDTO profile, BatchPipelineResultDTO result) {
        int suitable = result.shortlist().totalSuitable();
        int review = result.shortlist().totalNeedsReview();
        String summary = suitable + " suitable, " + review + " to review";
        if (suitable + review == 0) {
            summary = "no relevant opportunities";
        }
        return "Job Agent " + country(profile) + " " + date + ": " + summary;
    }

    public String format(LocalDate date, LoadedProfileDTO profile, BatchPipelineResultDTO result) {
        int suitable = result.shortlist().totalSuitable();
        int review = result.shortlist().totalNeedsReview();
        StringBuilder text = new StringBuilder("Job Agent opportunities for " + country(profile) + " — " + date + "\n\n");
        text.append("Suitable opportunities: ").append(suitable).append(". Roles needing review: ")
                .append(review).append(".\n");
        if (suitable + review == 0) {
            text.append("No relevant opportunities found in the current evaluated snapshot.\n");
        }
        text.append("Verify work-country permission and salary when they are unknown; neither is assumed.\n\n");
        List<String> entries = new ArrayList<>();
        result.shortlist().suitable().forEach(item -> entries.add(entry("Suitable", item)));
        result.shortlist().needsReview().forEach(item -> entries.add(entry("Needs review", item)));
        String diagnostics = diagnostics(result);
        int included = 0;
        for (String entry : entries) {
            if (included == 20) {
                break;
            }
            int omitted = suitable + review - included - 1;
            if (bytes(text.toString() + entry + "\n" + omission(Math.max(0, omitted)) + diagnostics) > MAX_BYTES) {
                break;
            }
            text.append(entry).append('\n');
            included++;
        }
        text.append(omission(Math.max(0, suitable + review - included)));
        text.append(diagnostics);
        return text.toString();
    }

    private static String country(LoadedProfileDTO profile) {
        return switch (profile.profile().workCountry()) {
            case "DE" -> "Germany";
            case "AT" -> "Austria";
            default -> throw new IllegalArgumentException("Unsupported report country");
        };
    }

    private static String omission(int count) {
        return count == 0 ? "" : count + " relevant opportunities omitted by the 20-entry or message-size limit.\n";
    }

    private static String entry(String category, VacancyEvaluationDTO item) {
        return category + ": " + clean(item.title(), 180) + "\n"
                + "  " + safeLink(item.originalUrl()) + "\n"
                + "  Matched: " + matched(item) + "\n"
                + "  Check: " + unknown(item) + "\n";
    }

    private static String matched(VacancyEvaluationDTO item) {
        List<String> reasons = item.reasons() == null ? List.of() : item.reasons();
        List<String> words = new ArrayList<>();
        if ("PREFERRED".equals(item.roleClassification())) {
            words.add("preferred role title");
        } else if ("RESERVE".equals(item.roleClassification())) {
            words.add("reserve role title");
        }
        if (reasons.contains("CONFIRMED_COUNTRY_REMOTE")) {
            words.add("source states country-specific remote work");
        }
        if (reasons.contains("CONFIRMED_EU_REMOTE")) {
            words.add("source states EU remote work with country permission");
        }
        if (reasons.contains("ACCEPTABLE_HYBRID_LOCATION")) {
            words.add("source states an acceptable hybrid location");
        }
        if (reasons.contains("SALARY_MEETS_SOFT_PREFERENCE")) {
            words.add("stated salary meets the preference");
        }
        return words.isEmpty() ? "role title matches the selected criteria" : String.join("; ", words);
    }

    private static String unknown(VacancyEvaluationDTO item) {
        List<String> unknowns = item.unknowns() == null ? List.of() : item.unknowns();
        List<String> words = new ArrayList<>();
        if (unknowns.contains("WORK_ELIGIBILITY_UNCONFIRMED")
                || unknowns.contains("CONFLICTING_COUNTRY_ELIGIBILITY")) {
            words.add("work-country permission");
        }
        if (unknowns.contains("SALARY_UNKNOWN") || unknowns.contains("SALARY_NOT_COMPARABLE")) {
            words.add("salary and its terms");
        }
        if (item.reasons() != null && item.reasons().contains("SALARY_BELOW_SOFT_PREFERENCE")) {
            words.add("stated salary is below the preference");
        }
        if (item.rejectedCount() > 0 || unknowns.size() > words.size()) {
            words.add("other unresolved evidence");
        }
        return words.isEmpty() ? "details against the source before applying" : String.join("; ", words);
    }

    private static String diagnostics(BatchPipelineResultDTO result) {
        return "\nRun diagnostics (snapshot; concurrent work may change):\n"
                + "Import: " + result.imported().created() + " created, " + result.imported().updated()
                + " updated, " + result.imported().failures() + " failures. "
                + "Evaluations stored: " + result.evaluated() + "; run failures: " + result.failed() + ". "
                + "Queue: " + result.published() + " sent; " + result.duplicates() + " duplicate deliveries.\n"
                + "Remaining: outbox " + (result.outbox().ready() + result.outbox().futureRetries()
                + result.outbox().activeClaims() + result.outbox().expiredClaims()
                + result.outbox().terminalFailures()) + ", queue "
                + (result.queue().visible() + result.queue().inFlight() + result.queue().delayed()) + ".\n"
                + ("FAILED".equals(result.outcome()) ? "Processing failed; review the task logs.\n"
                : "TIME_LIMIT".equals(result.outcome()) ? "Processing reached its time limit.\n"
                : "BACKLOG_REMAINS".equals(result.outcome()) ? "Processing remains pending.\n" : "");
    }

    private static String safeLink(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && value.length() <= 1000) {
                return clean(value, 1000);
            }
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Invalid source links are not rendered as clickable URLs.
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
