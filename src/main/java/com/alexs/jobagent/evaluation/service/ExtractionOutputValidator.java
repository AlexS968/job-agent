package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionClaimDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO;
import com.alexs.jobagent.evaluation.exception.ExtractionValidationException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExtractionOutputValidator {
    private static final String CLAIMS_FIELD = "claims";
    private static final String COUNTRY_PERMISSION = "COUNTRY_PERMISSION";
    private static final String COUNTRY_RESTRICTION = "COUNTRY_RESTRICTION";
    private static final Set<String> FACTS = Set.of("TITLE", "FAMILY", "SENIORITY", "REQUIRED_SKILL",
            "PREFERRED_SKILL", "ARRANGEMENT", COUNTRY_PERMISSION, COUNTRY_RESTRICTION,
            "SALARY_MIN", "SALARY_MAX", "SALARY_CURRENCY", "SALARY_PERIOD");
    private static final Set<String> UNCERTAINTIES = Set.of("STATED", "UNCERTAIN", "CONFLICTING");
    private final JsonMapper json;

    public ExtractionOutputValidator(JsonMapper json) {
        this.json = json;
    }

    public ExtractionOutputDTO validate(String content, ExtractionInputDTO input) {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (RuntimeException exception) {
            throw new ExtractionValidationException(-1, "INVALID_JSON");
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path(CLAIMS_FIELD).isArray()
                || root.path(CLAIMS_FIELD).size() > 24) {
            throw new ExtractionValidationException(-1, "INVALID_ROOT_SHAPE_OR_COUNT");
        }
        List<ExtractionClaimDTO> claims = new ArrayList<>();
        for (JsonNode node : root.path(CLAIMS_FIELD)) {
            claims.add(validateClaim(node, input, claims.size()));
        }
        return markConflicts(claims);
    }

    private static ExtractionClaimDTO validateClaim(JsonNode node, ExtractionInputDTO input, int index) {
        if (!node.isObject() || node.size() != 5) {
            throw new ExtractionValidationException(index, "INVALID_CLAIM_SHAPE");
        }
        String fact = bounded(node, "fact", 40, index);
        String value = bounded(node, "value", 120, index);
        String field = bounded(node, "sourceField", 40, index);
        String quote = bounded(node, "quote", 160, index);
        String uncertainty = bounded(node, "uncertainty", 20, index);
        if (!FACTS.contains(fact)) {
            throw new ExtractionValidationException(index, "UNSUPPORTED_FACT");
        }
        if (!UNCERTAINTIES.contains(uncertainty)) {
            throw new ExtractionValidationException(index, "UNSUPPORTED_UNCERTAINTY");
        }
        if (!input.sourceFields().containsKey(field)) {
            throw new ExtractionValidationException(index, "SOURCE_FIELD_UNAVAILABLE");
        }
        if (!normalize(input.sourceFields().get(field)).contains(normalize(quote))) {
            throw new ExtractionValidationException(index, "QUOTE_NOT_IN_SOURCE_FIELD");
        }
        value = canonicalValue(fact, value, quote);
        if (!validValue(fact, value)) {
            throw new ExtractionValidationException(index, "INVALID_FACT_VALUE");
        }
        // A generic remote label, country mention or office address cannot prove permission.
        if (COUNTRY_PERMISSION.equals(fact) && "STATED".equals(uncertainty)
                && !explicitPermission(quote, value, input.sourceFields().get(field))) {
            uncertainty = "UNCERTAIN";
        }
        return new ExtractionClaimDTO(fact, value, field, quote, uncertainty);
    }

    private static ExtractionOutputDTO markConflicts(List<ExtractionClaimDTO> claims) {
        Set<String> conflicts = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            ExtractionClaimDTO first = claims.get(i);
            for (int j = i + 1; j < claims.size(); j++) {
                ExtractionClaimDTO second = claims.get(j);
                if (differentSingleValuedFacts(first, second)) {
                    conflicts.add(first.fact());
                }
                if (contradictoryCountryClaims(first, second)) {
                    conflicts.add(first.fact());
                    conflicts.add(second.fact());
                }
            }
        }
        return new ExtractionOutputDTO(claims.stream().map(claim -> conflicts.contains(claim.fact())
                ? new ExtractionClaimDTO(claim.fact(), claim.value(), claim.sourceField(), claim.quote(),
                "CONFLICTING") : claim).toList());
    }

    private static boolean differentSingleValuedFacts(ExtractionClaimDTO first, ExtractionClaimDTO second) {
        return first.fact().equals(second.fact()) && !first.value().equalsIgnoreCase(second.value())
                && !first.fact().endsWith("SKILL");
    }

    private static boolean contradictoryCountryClaims(ExtractionClaimDTO first, ExtractionClaimDTO second) {
        return first.value().equalsIgnoreCase(second.value())
                && ((COUNTRY_PERMISSION.equals(first.fact()) && COUNTRY_RESTRICTION.equals(second.fact()))
                || (COUNTRY_RESTRICTION.equals(first.fact()) && COUNTRY_PERMISSION.equals(second.fact())));
    }

    private static String bounded(JsonNode node, String key, int max, int index) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.asText().isBlank() || value.asText().length() > max) {
            throw new ExtractionValidationException(index, "INVALID_" + key.toUpperCase(Locale.ROOT) + "_FIELD");
        }
        return value.asText();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static boolean explicitPermission(String quote, String country, String sourceField) {
        String sentence = stripTrailingPunctuation(normalize(quote));
        String normalizedCountry = Pattern.quote(normalize(country));
        String workFrom = "work(?: remotely)? from " + normalizedCountry;
        boolean clearStatement = sentence.matches(workFrom + " is (?:allowed|permitted)")
                || sentence.matches("(?:employees|staff|workers) (?:may|can) " + workFrom)
                || sentence.matches("(?:employees|staff|workers) are (?:allowed|permitted) to " + workFrom);
        if (!clearStatement) {
            return false;
        }
        // Require the entire source sentence: a selected substring must not hide negation or conditions.
        for (String sourceSentence : normalize(sourceField).split("[.!?]+")) {
            if (sentence.equals(sourceSentence.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingPunctuation(String sentence) {
        int end = sentence.length();
        while (end > 0 && ".!?".indexOf(sentence.charAt(end - 1)) >= 0) {
            end--;
        }
        return sentence.substring(0, end);
    }

    private static boolean validValue(String fact, String value) {
        return switch (fact) {
            case "ARRANGEMENT" -> Set.of("REMOTE", "HYBRID", "ONSITE").contains(value);
            case "SALARY_MIN", "SALARY_MAX" -> value.matches("\\d{1,9}(\\.\\d{1,2})?");
            case "SALARY_CURRENCY" -> value.matches("[A-Z]{3}");
            case "SALARY_PERIOD" -> Set.of("YEAR", "MONTH", "DAY", "HOUR").contains(value);
            default -> true;
        };
    }

    private static String canonicalValue(String fact, String value, String quote) {
        if ("SALARY_PERIOD".equals(fact) && "annual gross".equals(normalize(value))
                && normalize(quote).contains("annual gross")) {
            return "YEAR";
        }
        return value;
    }
}
