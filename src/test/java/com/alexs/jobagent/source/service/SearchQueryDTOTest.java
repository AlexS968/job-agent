package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.SearchQueryDTO;
import org.junit.jupiter.api.Test;

class SearchQueryDTOTest {
    @Test
    void rejectsQueriesThatCouldBypassPilotLimits() {
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryDTO(" ", null, "en", 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryDTO("x".repeat(401), null, "en", 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryDTO("jobs", null, "en", 11));
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryDTO("jobs", "Germany;drop", "en", 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryDTO("jobs", null, "english", 10));
        assertEquals(10, new SearchQueryDTO("jobs", "germany", "en", 10).maxResults());
    }
}
