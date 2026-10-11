package com.alexs.jobagent.outbox.dto;

public record DispatchResultDTO(int completed, int deferred, int failed, BacklogDTO backlog) {
}
