package com.alexs.jobagent.source.service;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GoogleGmailReadClient implements GmailReadClient {
    private static final int MAX_RESPONSE = 2_000_000;
    private static final URI TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");
    private static final String API = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    private static final String QUERY = "in:anywhere from:jobalerts-noreply@linkedin.com -in:spam -in:trash";
    private final HttpClient http;
    private final JsonMapper json;
    private String accessToken;

    @Autowired
    public GoogleGmailReadClient(JsonMapper json) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build(), json);
    }

    GoogleGmailReadClient(HttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public List<String> alertIds(int limit, LocalDate after, LocalDate before) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("Gmail intake limit must be between 1 and 20");
        }
        String query = QUERY + (after == null ? "" : " after:" + after.toString().replace('-', '/'))
                + (before == null ? "" : " before:" + before.toString().replace('-', '/'));
        String url = API + "?maxResults=" + limit + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode response = get(URI.create(url));
        List<String> ids = new ArrayList<>();
        JsonNode messages = response.path("messages");
        if (messages.isArray()) {
            for (JsonNode item : messages) {
                if (ids.size() >= limit) {
                    break;
                }
                String id = item.path("id").asText();
                if (!id.matches("[A-Za-z0-9_-]{1,128}")) {
                    throw new IllegalStateException("Gmail returned invalid message metadata");
                }
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    @Override
    public JsonNode alert(String messageId) {
        if (messageId == null || !messageId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Gmail message ID");
        }
        return get(URI.create(API + "/" + messageId + "?format=full"));
    }

    private JsonNode get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token()).GET().build();
        return send(request);
    }

    private synchronized String token() {
        if (accessToken != null) {
            return accessToken;
        }
        String configured = System.getenv("JOBAGENT_GMAIL_OAUTH_JSON");
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
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) {
                    throw new IllegalStateException("Gmail response exceeded the byte limit");
                }
                if (response.statusCode() != 200) {
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

    JsonNode parseResponse(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (RuntimeException invalid) {
            // Parser diagnostics may quote response bytes, including mailbox data or OAuth material.
            throw new IllegalStateException("Gmail response was invalid");
        }
    }
}
