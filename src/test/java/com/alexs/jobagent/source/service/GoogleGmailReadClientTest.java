package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GoogleGmailReadClientTest {
    @Test
    void malformedResponseDoesNotExposeResponseFragments() {
        var client = new GoogleGmailReadClient(new JsonMapper());
        var failure = assertThrows(IllegalStateException.class,
                () -> client.parseResponse("not-json synthetic-secret".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Gmail response was invalid", failure.getMessage());
        assertNull(failure.getCause());
    }
}
