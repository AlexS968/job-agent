package com.alexs.jobagent.source.service;

import java.util.List;
import java.time.LocalDate;
import tools.jackson.databind.JsonNode;

/** A bounded, read-only mailbox view. Implementations never modify message labels or state. */
public interface GmailReadClient {
    List<String> alertIds(int limit, LocalDate after, LocalDate before);
    JsonNode alert(String messageId);
}
