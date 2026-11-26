package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HimalayasPostingEvidenceTest {
    private final HimalayasPostingEvidence evidence = new HimalayasPostingEvidence(new JsonMapper());

    @Test
    void projectionRetainsExpiryAndSeparatesApplicationLink() {
        String raw = "{\"expiryDate\":1700000000,\"applicationLink\":\"https://example.test/apply\"}";
        assertEquals(Instant.ofEpochSecond(1700000000), evidence.expiry(raw));
        var metadata = evidence.reviewMetadata(raw, Instant.ofEpochSecond(1700000001));
        assertTrue(metadata.expired());
        assertEquals("https://example.test/apply", metadata.applicationLink());
    }

    @Test
    void unsupportedExpiryIsUnknownAndUnsafeApplicationLinkIsNotExposed() {
        for (String raw : new String[]{"{}", "{\"expiryDate\":null}", "{\"expiryDate\":\"tomorrow\"}",
                "{\"expiryDate\":9223372036854775807}"}) {
            assertNull(evidence.expiry(raw));
        }
        assertNull(evidence.reviewMetadata("{\"applicationLink\":\"javascript:alert(1)\"}", Instant.EPOCH).applicationLink());
    }
}
