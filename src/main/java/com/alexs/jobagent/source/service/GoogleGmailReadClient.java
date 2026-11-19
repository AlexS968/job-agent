package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.exception.InvalidGmailPageTokenException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GoogleGmailReadClient implements GmailReadClient, GmailCutoverClient {
    private static final Pattern GMAIL_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final int MAX_RESPONSE = 2_000_000;
    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final String API = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    private static final String QUERY = "in:anywhere from:jobalerts-noreply@linkedin.com -in:spam -in:trash";
    private final HttpClient http;
    private final JsonMapper json;
    private final Supplier<String> oauthConfiguration;
    private String accessToken;

    @Autowired
    public GoogleGmailReadClient(JsonMapper json) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build(), json);
    }

    GoogleGmailReadClient(HttpClient http, JsonMapper json) {
        this(http, json, () -> System.getenv("JOBAGENT_GMAIL_OAUTH_JSON"));
    }

    GoogleGmailReadClient(HttpClient http, JsonMapper json, Supplier<String> oauthConfiguration) {
        this.http = http;
        this.json = json;
        this.oauthConfiguration = oauthConfiguration;
    }

    @Override
    public List<String> alertIds(int limit, LocalDate after, LocalDate before) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Gmail intake limit must be between 1 and 20");
        }
        String query = QUERY + (after == null ? "" : " after:" + after.toString().replace('-', '/'))
                + (before == null ? "" : " before:" + before.toString().replace('-', '/'));
        return list(limit, query, null).ids();
    }

    @Override
    public Page livePage(long start, long end, String token) {
        return list(10, QUERY + " after:" + (start - 1) + " before:" + end, token);
    }

    private Page list(int limit, String query, String token) {
        String url = API + "?maxResults=" + limit + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode response = get(URI.create(token == null ? url : url + "&pageToken=" + encode(token)), token != null);
        if (!response.isObject()) {
            throw new IllegalStateException("Invalid Gmail list response");
        }
        return new Page(messageIds(response.path("messages"), limit), nextToken(response.get("nextPageToken")));
    }

    private static List<String> messageIds(JsonNode messages, int limit) {
        List<String> ids = new ArrayList<>();
        if (!messages.isMissingNode() && !messages.isArray()) {
            throw new IllegalStateException("Gmail returned invalid message metadata");
        }
        if (messages.isArray()) {
            for (JsonNode item : messages) {
                if (ids.size() >= limit) {
                    break;
                }
                String id = item.path("id").asText();
                if (!GMAIL_ID.matcher(id).matches()) {
                    throw new IllegalStateException("Gmail returned invalid message metadata");
                }
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static String nextToken(JsonNode next) {
        if (next != null && (!next.isString() || next.asText().isBlank() || next.asText().length() > 4096)) {
            throw new IllegalStateException("Invalid Gmail pagination metadata");
        }
        return next == null ? null : next.asText();
    }

    @Override
    public String processedLabel() {
        JsonNode labels = get(URI.create("https://gmail.googleapis.com/gmail/v1/users/me/labels")).path("labels");
        List<String> matches = new ArrayList<>();
        for (JsonNode label : labels) {
            if ("LinkedIn Processed".equals(label.path("name").asText()) && "user".equals(label.path("type").asText())) {
                matches.add(label.path("id").asText());
            }
        }
        if (matches.size() != 1 || !GMAIL_ID.matcher(matches.getFirst()).matches()) {
            throw new IllegalStateException("Existing processed label was not uniquely identified");
        }
        return matches.getFirst();
    }

    @Override
    public void applyLabels(String messageId, String labelId) {
        if (!GMAIL_ID.matcher(messageId).matches() || !GMAIL_ID.matcher(labelId).matches()) {
            throw new IllegalArgumentException("Invalid Gmail metadata");
        }
        String body = json.writeValueAsString(java.util.Map.of("addLabelIds", List.of(labelId),
                "removeLabelIds", List.of("UNREAD", "INBOX")));
        JsonNode result = send(HttpRequest.newBuilder(URI.create(API + "/" + messageId + "/modify"))
                .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (!messageId.equals(result.path("id").asText())) {
            throw new IllegalStateException("Gmail modification acknowledgement was invalid");
        }
    }

    @Override
    public JsonNode alert(String messageId) {
        if (messageId == null || !GMAIL_ID.matcher(messageId).matches()) {
            throw new IllegalArgumentException("Invalid Gmail message ID");
        }
        return get(URI.create(API + "/" + messageId + "?format=full"));
    }

    private JsonNode get(URI uri) {
        return get(uri, false);
    }

    private JsonNode get(URI uri, boolean continuation) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token()).GET().build();
        return send(request, continuation);
    }

    private synchronized String token() {
        if (accessToken != null) {
            return accessToken;
        }
        String configured = oauthConfiguration.get();
        if (configured == null || configured.length() > 16_384) {
            throw new IllegalStateException("Private Gmail OAuth configuration is unavailable");
        }
        JsonNode secret;
        try {
            secret = json.readTree(configured);
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Private Gmail OAuth configuration is invalid");
        }
        String clientId = secret.path("client_id").asText();
        String clientSecret = secret.path("client_secret").asText();
        String refreshToken = secret.path("refresh_token").asText();
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            throw new IllegalStateException("Private Gmail OAuth configuration is incomplete");
        }
        String form = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&refresh_token=" + encode(refreshToken) + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(TOKEN_URI).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        JsonNode result = send(request);
        String issued = result.path("access_token").asText();
        if (issued.isBlank()) {
            throw new IllegalStateException("Gmail OAuth refresh returned no token");
        }
        accessToken = issued;
        return issued;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonNode send(HttpRequest request) {
        return send(request, false);
    }

    private JsonNode send(HttpRequest request, boolean continuation) {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) {
                    throw new IllegalStateException("Gmail response exceeded the byte limit");
                }
                if (response.statusCode() != 200) {
                    if (continuation && response.statusCode() == 400 && invalidPageToken(bytes)) {
                        throw new InvalidGmailPageTokenException();
                    }
                    throw new IllegalStateException("Gmail request failed with HTTP " + response.statusCode());
                }
                return parseResponse(bytes);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail request interrupted", interrupted);
        } catch (IOException failure) {
            throw new IllegalStateException("Gmail request failed", failure);
        }
    }

    private boolean invalidPageToken(byte[] bytes) {
        JsonNode error;
        try {
            error = json.readTree(bytes).path("error");
        } catch (RuntimeException invalid) {
            return false;
        }
        if (error.path("message").asText().matches("(?i)invalid (page token|pagetoken)\\.?")) {
            return true;
        }
        for (JsonNode detail : error.path("errors")) {
            String reason = detail.path("reason").asText();
            if ("invalidPageToken".equals(reason)
                    || ("invalid".equals(reason) && "pageToken".equals(detail.path("location").asText()))) {
                return true;
            }
        }
        return false;
    }

    JsonNode parseResponse(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (RuntimeException invalid) {
            // Parser diagnostics may quote response bytes, including mailbox data or OAuth material.
            throw new IllegalStateException("Gmail response was invalid");
        }
    }
}
