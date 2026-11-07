package com.alexs.jobagent.outbox.dto;

public record SqsQueueBacklogDTO(int visible, int inFlight, int delayed) { }
