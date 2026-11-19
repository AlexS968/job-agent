package com.alexs.jobagent.source.dto;

import java.time.Instant;
import java.util.List;

public record LinkedInAlertDTO(String messageId, Instant observedAt, List<CardDTO> cards,
                               int unsupportedLinks) {
    public record CardDTO(String jobId, String title, String originalUrl,
                          String advertisedDirection, String directionEvidence, String advertisedEmployer) {
        public boolean hasAdvertisedTitle() {
            return !title.equals("LinkedIn job " + jobId);
        }
    }
}
