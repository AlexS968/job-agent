package com.alexs.jobagent.batch.dto;

import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;

public record BatchPipelineResultDTO(String outcome, ActiveSourceImportResultDTO imported,
                                     int published, int evaluated, int duplicates, int stale, int failed,
                                     BacklogDTO outbox, SqsQueueBacklogDTO queue, ShortlistDTO shortlist) { }
