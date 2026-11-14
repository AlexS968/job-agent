package com.alexs.jobagent.batch.dto;

public record PreparedResultDTO(String subject, String body, int distinctNew,
                                int germanyNew, int austriaNew, int distinctRelevant,
                                int germanyRelevant, int austriaRelevant) { }
