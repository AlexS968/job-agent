package com.alexs.jobagent.source.dto;

public record LinkedInIntakeResultDTO(int messages, int replayed, int cards, int created,
                                      int updated, int reviewRequired) { }
