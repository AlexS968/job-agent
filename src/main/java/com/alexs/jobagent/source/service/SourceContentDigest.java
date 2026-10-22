package com.alexs.jobagent.source.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SourceContentDigest {
    private final JsonMapper json;

    public SourceContentDigest(JsonMapper json) {
        this.json = json;
    }

    public String digest(JsonNode node, Set<String> excludedRootFields) {
        StringBuilder canonical = new StringBuilder();
        canonical(node, canonical, excludedRootFields, true);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void canonical(JsonNode node, StringBuilder result, Set<String> excludedRootFields, boolean root) {
        if (node.isObject()) {
            result.append('{');
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(property -> {
                if (!root || !excludedRootFields.contains(property.getKey())) {
                    sorted.put(property.getKey(), property.getValue());
                }
            });
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                result.append(json.writeValueAsString(entry.getKey())).append(':');
                canonical(entry.getValue(), result, excludedRootFields, false);
            }
            result.append('}');
        } else if (node.isArray()) {
            result.append('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    result.append(',');
                }
                first = false;
                canonical(child, result, excludedRootFields, false);
            }
            result.append(']');
        } else {
            result.append(json.writeValueAsString(node));
        }
    }
}
