package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LinkedInAlertParserTest {
    private final JsonMapper json = new JsonMapper();
    private final LinkedInAlertParser parser = new LinkedInAlertParser();

    @Test
    void countsCardsRatherThanAdvertisedSubjectAndCollapsesMimeRepetitions() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"
                        + "<a href='https://www.linkedin.com/comm/jobs/view/654321/?trk=mail'>Backend Engineer</a>",
                "https://www.linkedin.com/jobs/view/123456/"));
        assertEquals(2, parsed.cards().size());
        assertEquals("Java Engineer", parsed.cards().getFirst().title());
        assertEquals("https://www.linkedin.com/jobs/view/123456/", parsed.cards().getFirst().originalUrl());
        assertEquals(0, parsed.unsupportedLinks());
    }

    @Test
    void unrecognizedJobLinkIsVisibleAndSenderIsRequired() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<a href='https://www.linkedin.com/jobs/search/'>Search</a>", ""));
        assertEquals(0, parsed.cards().size());
        assertEquals(1, parsed.unsupportedLinks());
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(message("attacker@example.test",
                        "<a href='https://www.linkedin.com/jobs/view/123456/'>Java</a>", "")));
    }

    @Test
    void plainTextSentencePunctuationDoesNotObscureAJobId() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com", "",
                "See https://www.linkedin.com/jobs/view/777777/)."));
        assertEquals(1, parsed.cards().size());
        assertEquals("777777", parsed.cards().getFirst().jobId());
        assertEquals("UNKNOWN", parsed.cards().getFirst().advertisedDirection());
        assertEquals("LinkedIn job 777777", parsed.cards().getFirst().title());
    }

    @Test
    void cardLocationDistinguishesAdvertisedDirectionsWithoutInferringEligibility() {
        String[] locations = {"Berlin, Germany", "Vienna, Austria", "Remote Europe",
                "Remote Worldwide", "Remote Germany or Austria"};
        String[] expected = {"GERMANY", "AUSTRIA", "REMOTE_EUROPE", "REMOTE_WORLDWIDE", "UNKNOWN"};
        for (int index = 0; index < locations.length; index++) {
            var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                    "<div><a href='https://www.linkedin.com/jobs/view/123456/'>Java Engineer</a>"
                            + "<span class='job-location'>" + locations[index] + "</span></div>", ""));
            assertEquals(expected[index], parsed.cards().getFirst().advertisedDirection());
            assertEquals(locations[index], parsed.cards().getFirst().directionEvidence());
        }
        assertThrows(IllegalArgumentException.class, () -> parser.parse(message("jobs@linkedin.com",
                "<a href='https://www.linkedin.com/jobs/view/123456/'>Java</a>", "")));
    }

    @Test
    void conflictingCardLocationsRemainUnknownAndDoNotBorrowOtherCardEvidence() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<div><a href='https://www.linkedin.com/jobs/view/123456/'>Java</a>"
                        + "<span class='job-location'>Berlin, Germany</span></div>"
                        + "<div><a href='https://www.linkedin.com/jobs/view/123456/'>Java</a>"
                        + "<span class='job-location'>Vienna, Austria</span></div>"
                        + "<a href='https://www.linkedin.com/jobs/view/654321/'>Backend</a>", ""));
        assertEquals("UNKNOWN", parsed.cards().getFirst().advertisedDirection());
        assertNull(parsed.cards().getFirst().directionEvidence());
        assertEquals("UNKNOWN", parsed.cards().get(1).advertisedDirection());
    }

    private tools.jackson.databind.JsonNode message(String sender, String html, String plain) {
        String htmlData = Base64.getUrlEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        String plainData = Base64.getUrlEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        return json.readTree(json.writeValueAsString(Map.of(
                "id", "synthetic-message-1", "internalDate", "1795000000000",
                "payload", Map.of("headers", new Object[]{Map.of("name", "From", "value", sender),
                        Map.of("name", "Subject", "value", "10 jobs")},
                        "mimeType", "multipart/alternative", "parts", new Object[]{
                                Map.of("mimeType", "text/html", "body", Map.of("data", htmlData)),
                                Map.of("mimeType", "text/plain", "body", Map.of("data", plainData))}))));
    }
}
