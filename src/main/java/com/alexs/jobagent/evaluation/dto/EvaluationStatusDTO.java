package com.alexs.jobagent.evaluation.dto;

import java.util.List;

public record EvaluationStatusDTO(long id, long vacancyId, long sourceItemId, String revision,
                                  String profileId, String profileVersion, String criteriaHash,
                                  String policyVersion, String decision, List<String> reasons,
                                  List<String> unknowns, List<String> evidence) { }
