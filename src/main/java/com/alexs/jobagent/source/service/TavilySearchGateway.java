package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.SearchQueryDTO;
import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.dto.SearchResultDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TavilySearchGateway implements SearchGateway {
    private static final URI ENDPOINT = URI.create("https://api.tavily.com/search");
    private final BoundedSourceHttpTransport transport;
    private final JsonMapper json;
    private final Supplier<String> keySupplier;

    @Autowired
    public TavilySearchGateway(BoundedSourceHttpTransport transport, JsonMapper json) {
        this(transport, json, () -> System.getenv("TAVILY_API_KEY"));
    }

    TavilySearchGateway(BoundedSourceHttpTransport transport, JsonMapper json, Supplier<String> keySupplier) {
        this.transport = transport;
        this.json = json;
        this.keySupplier = keySupplier;
    }

    @Override
    public SearchResponseDTO search(SearchQueryDTO query) throws SearchFailureException {
        String key = keySupplier.get();
        if (key == null || key.isBlank()) {
            throw new SearchFailureException(SearchFailureException.Kind.AUTHENTICATION, null, null);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query.query());
        body.put("country", query.country());
        body.put("language", query.language());
        body.put("max_results", query.maxResults());
        body.put("search_depth", "basic");
        body.put("auto_parameters", false);
        body.put("include_answer", false);
        body.put("include_raw_content", false);
        body.put("include_images", false);
        body.put("include_usage", true);
        BoundedSourceHttpTransport.Response response;
        try {
            response = transport.postJson(ENDPOINT, key, json.writeValueAsString(body));
        } catch (BoundedSourceHttpTransport.RequestInterruptedException interrupted) {
            throw new SearchFailureException(SearchFailureException.Kind.CANCELLED, null, null);
        } catch (BoundedSourceHttpTransport.ResponseTooLargeException tooLarge) {
            throw new SearchFailureException(SearchFailureException.Kind.INVALID_RESPONSE, null, null);
        } catch (IOException transportFailure) {
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, null, null);
        }
        String requestId = response.firstHeader("X-Request-Id");
        if (response.status() == 429 || response.status() >= 500) {
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, requestId,
                    response.firstHeader("Retry-After"));
        }
        if (response.status() == 432 || response.status() == 433) {
            throw new SearchFailureException(SearchFailureException.Kind.QUOTA, requestId, null);
        }
        if (response.status() == 401 || response.status() == 403) {
            throw new SearchFailureException(SearchFailureException.Kind.AUTHENTICATION, requestId, null);
        }
        if (response.status() != 200) {
            throw new SearchFailureException(SearchFailureException.Kind.PERMANENT, requestId, null);
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                throw new IllegalArgumentException("Missing results");
            }
            List<SearchResultDTO> normalized = new ArrayList<>();
            int rank = 0;
            for (JsonNode result : results) {
                if (rank >= query.maxResults()) {
                    break;
                }
                rank++;
                String url = SearchCandidateUrl.normalize(result.path("url").asText(""));
                if (url != null) {
                    normalized.add(new SearchResultDTO(url, result.path("title").asText(null),
                            result.path("content").asText(null), rank));
                }
            }
            String parsedId = root.path("request_id").asText(requestId);
            JsonNode credits = root.path("usage").path("credits");
            Integer billed = credits.isIntegralNumber() && credits.asInt() >= 0 ? credits.asInt() : null;
            return new SearchResponseDTO(List.copyOf(normalized), parsedId, billed);
        } catch (RuntimeException invalid) {
            throw new SearchFailureException(SearchFailureException.Kind.INVALID_RESPONSE, requestId, null);
        }
    }
}
