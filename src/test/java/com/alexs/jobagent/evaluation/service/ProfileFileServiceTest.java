package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProfileFileServiceTest {
    @TempDir Path directory;
    private final ProfileFileService profiles = new ProfileFileService(new JsonMapper());

    @Test
    void reorderedJsonHasSameCriteriaHashButChangedCriteriaDoesNot() throws Exception {
        String body = """
                {"profileId":"fictional","version":"v1","workCountry":"DE",
                 "preferredRoleTerms":["Backend Engineer"],"reserveRoleTerms":[],
                 "excludedTitleTerms":[],"excludedArrangements":["ONSITE"],
                 "allowedHybridLocations":[],"allowEuRemote":true,"salaryPreference":null}
                """;
        Path first = directory.resolve("first.json");
        Files.writeString(first, body);
        Path reordered = directory.resolve("reordered.json");
        Files.writeString(reordered, body.replace("\"profileId\":\"fictional\",\"version\":\"v1\"",
                "\"version\":\"v1\",\"profileId\":\"fictional\""));
        assertEquals(profiles.load(first.toString()).criteriaHash(), profiles.load(reordered.toString()).criteriaHash());
        Path changed = directory.resolve("changed.json");
        Files.writeString(changed, body.replace("Backend Engineer", "Java Engineer"));
        assertNotEquals(profiles.load(first.toString()).criteriaHash(), profiles.load(changed.toString()).criteriaHash());
    }

    @Test
    void missingInvalidAndRepositoryLocalFilesFailSafely() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> profiles.load(null));
        String absent = directory.resolve("absent.json").toString();
        assertThrows(IllegalArgumentException.class, () -> profiles.load(absent));
        Path bad = directory.resolve("bad.json");
        Files.writeString(bad, "{private-broken}");
        String badFile = bad.toString();
        var failure = assertThrows(IllegalArgumentException.class, () -> profiles.load(badFile));
        assertFalse(failure.getMessage().contains("private-broken"));
        assertThrows(IllegalArgumentException.class,
                () -> profiles.load("src/main/resources/synthetic/source-item.json"));
        Files.writeString(bad, "{\"profileId\":\"x\",\"version\":\"v1\",\"workCountry\":\"Germany\"}");
        assertThrows(IllegalArgumentException.class, () -> profiles.load(badFile));
    }
}
