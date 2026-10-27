package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfileFileService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private final JsonMapper json;

    public ProfileFileService(JsonMapper json) {
        this.json = json;
    }

    public LoadedProfileDTO load(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("A private profile file is required");
        }
        SearchProfileDTO profile;
        try {
            Path file = Path.of(fileName).toRealPath();
            Path repository = Path.of("").toAbsolutePath().toRealPath();
            if (file.startsWith(repository) || Files.size(file) > 32_768) {
                throw new IllegalArgumentException("Profile file must be outside the repository and at most 32 KiB");
            }
            profile = json.readValue(Files.readAllBytes(file), SearchProfileDTO.class);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Private profile file is unavailable or invalid");
        }
        validate(profile);
        return new LoadedProfileDTO(profile, sha256(json.writeValueAsBytes(profile)));
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private void validate(SearchProfileDTO profile) {
        if (profile == null || !matches(ID, profile.profileId()) || !matches(VERSION, profile.version())
                || !matches(COUNTRY, profile.workCountry())) {
            throw new IllegalArgumentException("Private profile identity or work country is invalid");
        }
        terms(profile.preferredRoleTerms(), true);
        terms(profile.reserveRoleTerms(), false);
        terms(profile.excludedTitleTerms(), false);
        terms(profile.allowedHybridLocations(), false);
        if (profile.excludedArrangements() == null || profile.excludedArrangements().size() > 3
                || profile.excludedArrangements().stream().anyMatch(value ->
                !Set.of("REMOTE", "HYBRID", "ONSITE").contains(value))) {
            throw new IllegalArgumentException("Private profile exclusion settings are invalid");
        }
        var salary = profile.salaryPreference();
        if (salary != null && (salary.amount() <= 0 || salary.amount() > 100_000_000
                || salary.currency() == null || !salary.currency().matches("[A-Z]{3}")
                || !Set.of("YEAR", "MONTH").contains(salary.period()))) {
            throw new IllegalArgumentException("Private salary preference is invalid");
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static void terms(List<String> values, boolean required) {
        if (values == null || values.size() > 16 || required && values.isEmpty()) {
            throw new IllegalArgumentException("Private profile terms are invalid");
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 80 || !value.equals(value.trim())
                    || !seen.add(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Private profile terms are invalid");
            }
        }
    }
}
