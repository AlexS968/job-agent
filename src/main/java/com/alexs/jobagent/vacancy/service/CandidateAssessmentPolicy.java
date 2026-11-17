package com.alexs.jobagent.vacancy.service;

import com.alexs.jobagent.vacancy.dto.CandidateAssessmentDTO;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

/** A conservative role-candidate screen, separate from profile fit and work eligibility. */
@Service
public class CandidateAssessmentPolicy {
    public static final String VERSION = "java-backend-candidate-v2";
    private static final Pattern DIRECT_TITLE = Pattern.compile(
            "(?i)\\b(?:java|spring(?: boot)?)\\b.*\\b(?:engineer|developer|architect)\\b"
            + "|\\b(?:engineer|developer|architect)\\b.*\\b(?:java|spring(?: boot)?)\\b");
    private static final Pattern GENERIC_TITLE = Pattern.compile(
            "(?i)\\b(?:software|platform|application|full[- ]?stack|backend|back-end)\\b.*\\b(?:engineer|developer)\\b"
            + "|\\b(?:engineer|developer)\\b.*\\b(?:software|platform|application|full[- ]?stack|backend|back-end)\\b");
    private static final Pattern JAVA_STACK = Pattern.compile("(?i)\\b(?:java|spring(?: boot)?)\\b");
    private static final Pattern BACKEND_WORK = Pattern.compile(
            "(?i)\\b(?:backend|back-end|server-side|microservices?|rest(?:ful)? apis?)\\b");
    private static final Pattern NEGATED_STACK = Pattern.compile(
            "(?i)\\b(?:no|not|without)\\s+(?:experience\\s+(?:with|in)\\s+)?(?:java|spring)\\b"
            + "|\\b(?:java|spring)\\s+(?:is\\s+)?not\\s+(?:used|required)\\b");
    private static final Pattern OTHER_STACK = Pattern.compile(
            "(?i)\\b(?:python|ruby|php|golang|go|c#|dotnet|\\.net)\\b");

    public CandidateAssessmentDTO assess(String title, String description) {
        if (title == null || title.isBlank()) {
            return new CandidateAssessmentDTO("REVIEW", "MISSING_TITLE");
        }
        String plainTitle = Jsoup.parse(title).text().toLowerCase(Locale.ROOT);
        if (DIRECT_TITLE.matcher(plainTitle).find()) {
            return new CandidateAssessmentDTO("CANDIDATE", "JAVA_BACKEND_TITLE");
        }
        if (!GENERIC_TITLE.matcher(plainTitle).find()) {
            return new CandidateAssessmentDTO("NOT_CANDIDATE", "OTHER_ROLE_TITLE");
        }
        String plainDescription = description == null ? "" : Jsoup.parse(description).text();
        if (NEGATED_STACK.matcher(plainDescription).find()) {
            return new CandidateAssessmentDTO("REVIEW", "AMBIGUOUS_STACK_EVIDENCE");
        }
        if (JAVA_STACK.matcher(plainDescription).find()
                && (BACKEND_WORK.matcher(plainDescription).find()
                || BACKEND_WORK.matcher(plainTitle).find())) {
            return new CandidateAssessmentDTO("CANDIDATE", "GENERIC_TITLE_WITH_JAVA_BACKEND_EVIDENCE");
        }
        if (OTHER_STACK.matcher(plainDescription).find()) {
            return new CandidateAssessmentDTO("REVIEW", "OTHER_STACK_EVIDENCE_WITHOUT_JAVA_CONFIRMATION");
        }
        return new CandidateAssessmentDTO("REVIEW", "GENERIC_TITLE_WITHOUT_CLEAR_STACK_EVIDENCE");
    }
}
