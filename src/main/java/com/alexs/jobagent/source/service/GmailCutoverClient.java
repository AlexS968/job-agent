package com.alexs.jobagent.source.service;

import java.util.List;

public interface GmailCutoverClient {
    record Page(List<String> ids, String nextToken) {}
    Page livePage(long start, long end, String token);
    String processedLabel();
    void applyLabels(String messageId, String labelId);
}
