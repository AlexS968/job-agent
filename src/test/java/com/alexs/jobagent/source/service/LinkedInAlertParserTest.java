package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alexs.jobagent.source.exception.LinkedInAlertException;
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

    @Test
    void rejectsMalformedEnvelopeAndMimeWithoutRetainingBody() {
        assertEquals("INVALID_MESSAGE", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(null)).reason());
        assertEquals("INVALID_METADATA", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(json.readTree("{}"))).reason());
        assertEquals("INVALID_DATE", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(messageWithPart("text/html", "", "not-a-date"))).reason());
        assertEquals("UNSUPPORTED_SENDER", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(message("other@linkedin.com", "", ""))).reason());
        assertEquals("INVALID_ENCODING", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(messageWithPart("text/html", "not-base64!", "1795000000000"))).reason());
        assertEquals("PART_TOO_LARGE", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(messageWithPart("text/html", "A".repeat(1_024_001),
                        "1795000000000"))).reason());
        assertEquals(1, parser.parse(messageWithPart("text/html", null,
                "1795000000000")).unsupportedLinks());
    }

    @Test
    void acceptsDirectQueryJobIdButKeepsRedirectsAndUnsupportedPathsForReview() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<a href='https://www.linkedin.com/jobs/search/?currentJobId=123456'>Java Engineer</a>"
                        + "<a href='https://lnkd.in/short'>Short link</a>"
                        + "<a href='https://www.linkedin.com/jobs/search/'>Unknown job link</a>"
                        + "<a href='https://www.linkedin.com/jobs/view/not-a-number/'>Unsupported job</a>", ""));
        assertEquals(1, parsed.cards().size());
        assertEquals("123456", parsed.cards().getFirst().jobId());
        assertEquals(3, parsed.unsupportedLinks());
        var malformed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<a href='https://www.linkedin.com/jobs/search/?jobId=%GG'>Bad query</a>", ""));
        assertEquals(1, malformed.unsupportedLinks());
    }

    @Test
    void ambiguousCardMarkupDoesNotBorrowLocationFromAnotherJob() {
        var parsed = parser.parse(message("jobalerts-noreply@linkedin.com",
                "<div><a href='https://www.linkedin.com/jobs/view/123456/'>Java</a>"
                        + "<a href='https://www.linkedin.com/jobs/view/654321/'>Go</a>"
                        + "<span class='job-location'>Vienna, Austria</span></div>"
                        + "<div><a href='https://www.linkedin.com/jobs/view/777777/'>Backend</a>"
                        + "<span data-job-location='Remote Europe'></span></div>", ""));
        assertEquals("UNKNOWN", parsed.cards().get(0).advertisedDirection());
        assertEquals("UNKNOWN", parsed.cards().get(1).advertisedDirection());
        assertEquals("REMOTE_EUROPE", parsed.cards().get(2).advertisedDirection());
        assertEquals("Remote Europe", parsed.cards().get(2).directionEvidence());
    }

    @Test
    void rejectsCardAndPartFloodsAtTheirBoundaries() {
        StringBuilder links = new StringBuilder();
        for (int index = 1000; index <= 1100; index++) {
            links.append("<a href='https://www.linkedin.com/jobs/view/").append(index)
                    .append("/'>Java</a>");
        }
        assertEquals("TOO_MANY_CARDS", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(message("jobalerts-noreply@linkedin.com", links.toString(), ""))).reason());
        String unsupported = "<a href='https://www.linkedin.com/jobs/search/'>Unknown</a>".repeat(101);
        assertEquals("TOO_MANY_UNSUPPORTED_LINKS", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(message("jobalerts-noreply@linkedin.com", unsupported, ""))).reason());
        Object[] parts = new Object[61];
        for (int index = 0; index < parts.length; index++) {
            parts[index] = Map.of("mimeType", "application/octet-stream");
        }
        var message = json.readTree(json.writeValueAsString(Map.of(
                "id", "synthetic-message-1", "internalDate", "1795000000000",
                "payload", Map.of("headers", new Object[]{Map.of("name", "From",
                        "value", "jobalerts-noreply@linkedin.com")},
                        "mimeType", "multipart/mixed", "parts", parts))));
        assertEquals("TOO_MANY_PARTS", assertThrows(LinkedInAlertException.class,
                () -> parser.parse(message)).reason());
    }

    private tools.jackson.databind.JsonNode messageWithPart(String mime, String encoded, String date) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("headers", new Object[]{Map.of("name", "From", "value", "jobalerts-noreply@linkedin.com")});
        payload.put("mimeType", mime);
        payload.put("body", encoded == null ? Map.of() : Map.of("data", encoded));
        return json.readTree(json.writeValueAsString(Map.of(
                "id", "synthetic-message-1", "internalDate", date, "payload", payload)));
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
