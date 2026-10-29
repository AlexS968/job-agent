package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionClaimDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO;
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
    private static final Set<String> FACTS = Set.of("TITLE", "FAMILY", "SENIORITY", "REQUIRED_SKILL",
            "PREFERRED_SKILL", "ARRANGEMENT", "COUNTRY_PERMISSION", "COUNTRY_RESTRICTION",
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
            throw new IllegalArgumentException("Invalid extraction JSON");
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("claims").isArray()
                || root.path("claims").size() > 24) {
            throw new IllegalArgumentException("Invalid extraction shape or claim count");
        }
        List<ExtractionClaimDTO> claims = new ArrayList<>();
        for (JsonNode node : root.path("claims")) {
            if (!node.isObject() || node.size() != 5) {
                throw new IllegalArgumentException("Invalid extraction claim shape");
            }
            String fact = bounded(node, "fact", 40);
            String value = bounded(node, "value", 120);
            String field = bounded(node, "sourceField", 40);
            String quote = bounded(node, "quote", 160);
            String uncertainty = bounded(node, "uncertainty", 20);
            if (!FACTS.contains(fact) || !UNCERTAINTIES.contains(uncertainty)
                    || !input.sourceFields().containsKey(field)
                    || !normalize(input.sourceFields().get(field)).contains(normalize(quote))
                    || !validValue(fact, value)) {
                throw new IllegalArgumentException("Extraction claim lacks supplied evidence");
            }
            // A generic remote label, country mention or office address cannot prove permission.
            if ("COUNTRY_PERMISSION".equals(fact) && "STATED".equals(uncertainty)
                    && !explicitPermission(quote, value, input.sourceFields().get(field))) {
                uncertainty = "UNCERTAIN";
            }
            claims.add(new ExtractionClaimDTO(fact, value, field, quote, uncertainty));
        }
        Set<String> conflicts = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            ExtractionClaimDTO first = claims.get(i);
            for (int j = i + 1; j < claims.size(); j++) {
                ExtractionClaimDTO second = claims.get(j);
                if (first.fact().equals(second.fact()) && !first.value().equalsIgnoreCase(second.value())
                        && !first.fact().endsWith("SKILL")) {
                    conflicts.add(first.fact());
                }
                if (first.value().equalsIgnoreCase(second.value())
                        && (("COUNTRY_PERMISSION".equals(first.fact()) && "COUNTRY_RESTRICTION".equals(second.fact()))
                        || ("COUNTRY_RESTRICTION".equals(first.fact()) && "COUNTRY_PERMISSION".equals(second.fact())))) {
                    conflicts.add(first.fact());
                    conflicts.add(second.fact());
                }
            }
        }
        return new ExtractionOutputDTO(claims.stream().map(claim -> conflicts.contains(claim.fact())
                ? new ExtractionClaimDTO(claim.fact(), claim.value(), claim.sourceField(), claim.quote(),
                "CONFLICTING") : claim).toList());
    }

    private static String bounded(JsonNode node, String key, int max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.asText().isBlank() || value.asText().length() > max) {
            throw new IllegalArgumentException("Invalid extraction claim field");
        }
        return value.asText();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static boolean explicitPermission(String quote, String country, String sourceField) {
        String sentence = normalize(quote).replaceAll("[.!?]+$", "");
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

    private static boolean validValue(String fact, String value) {
        return switch (fact) {
            case "ARRANGEMENT" -> Set.of("REMOTE", "HYBRID", "ONSITE").contains(value);
            case "SALARY_MIN", "SALARY_MAX" -> value.matches("[0-9]{1,9}(\\.[0-9]{1,2})?");
            case "SALARY_CURRENCY" -> value.matches("[A-Z]{3}");
            case "SALARY_PERIOD" -> Set.of("YEAR", "MONTH", "DAY", "HOUR").contains(value);
            default -> true;
        };
    }
}
