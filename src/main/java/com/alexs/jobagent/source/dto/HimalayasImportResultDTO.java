package com.alexs.jobagent.source.dto;

import java.util.Map;

public record HimalayasImportResultDTO(int requests, int fetched, int distinct, int roleRelevant,
                                      int created, int updated, int unchanged, int failed,
                                      Map<String, String> failedFilters) { }
