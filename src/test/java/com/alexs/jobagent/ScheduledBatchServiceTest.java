package com.alexs.jobagent;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.batch.service.BatchReportFormatter;
import com.alexs.jobagent.batch.service.ReportGateway;
import com.alexs.jobagent.batch.service.ScheduledBatchService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ScheduledBatchServiceTest {
    private final ScheduledBatchRepository repository = mock(ScheduledBatchRepository.class);
    private final BatchPipelineService pipeline = mock(BatchPipelineService.class);
    private final ReportGateway gateway = mock(ReportGateway.class);
    private final BatchReportFormatter formatter = new BatchReportFormatter();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T18:00:00Z"), ZoneOffset.UTC);
    private final LocalDate date = LocalDate.of(2026, 11, 5);
    private final LoadedProfileDTO profile = new LoadedProfileDTO(new SearchProfileDTO("fictional-demo", "v1",
            "Germany", List.of(), List.of(), List.of(), List.of(), List.of(), false, null), "synthetic-hash");

    private ScheduledBatchService service() {
        return new ScheduledBatchService(repository, pipeline, formatter, gateway, clock);
    }

    @Test
    void duplicateSlotNeverRepeatsWorkOrEmail() {
        when(repository.start(date, "fictional-demo", "v1")).thenReturn(false);
        when(repository.exists(date)).thenReturn(true);
        service().run(profile);
        verify(pipeline, never()).run(any(), any(Integer.class), any(), any(Integer.class));
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void successfulReportPublishesAfterPreparedState() {
        when(repository.start(date, "fictional-demo", "v1")).thenReturn(true);
        when(pipeline.run(eq(profile), eq(50), any(), eq(20))).thenReturn(result("NO_NEW_VACANCIES"));
        when(gateway.publish(any(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "synthetic-message-id";
        });
        service().run(profile);
        var order = org.mockito.Mockito.inOrder(repository, gateway);
        order.verify(repository).transition(eq(date), eq("RUNNING"), eq("PREPARED"), any(), isNull());
        order.verify(repository).transition(date, "PREPARED", "PUBLISHING", null, null);
        order.verify(gateway).publish(any(), any());
        order.verify(repository).transition(date, "PUBLISHING", "PUBLISHED", null, "synthetic-message-id");
    }

    @Test
    void ambiguousPublicationRetainsIdentityAndDoesNotRetry() {
        when(repository.start(date, "fictional-demo", "v1")).thenReturn(true);
        when(pipeline.run(eq(profile), eq(50), any(), eq(20))).thenReturn(result("BACKLOG_REMAINS"));
        when(gateway.publish(any(), any())).thenThrow(new IllegalStateException("synthetic provider timeout"));
        assertThrows(IllegalStateException.class, () -> service().run(profile));
        verify(repository).transition(date, "PUBLISHING", "UNCERTAIN", null, null);
        verify(gateway).publish(any(), any());
    }

    @Test
    void failedProcessingDoesNotSendAReport() {
        when(repository.start(date, "fictional-demo", "v1")).thenReturn(true);
        when(pipeline.run(eq(profile), eq(50), any(), eq(20))).thenReturn(result("FAILED"));
        assertThrows(IllegalStateException.class, () -> service().run(profile));
        verify(repository).transition(date, "RUNNING", "FAILED", null, null);
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void trackingFailureDoesNotReplaceOriginalProcessingFailure() {
        when(repository.start(date, "fictional-demo", "v1")).thenReturn(true);
        IllegalStateException original = new IllegalStateException("synthetic processing failure");
        when(pipeline.run(eq(profile), eq(50), any(), eq(20))).thenThrow(original);
        doThrow(new IllegalStateException("synthetic tracking failure")).when(repository)
                .transition(date, "RUNNING", "FAILED", null, null);
        assertEquals(original, assertThrows(IllegalStateException.class, () -> service().run(profile)));
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void ambientTransactionIsRejectedBeforeRunReservation() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service().run(profile));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(repository, never()).start(any(), any(), any());
    }

    @Test
    void reportContainsBoundedEntriesAndExplicitEmptyResult() {
        String empty = formatter.format(date, profile, result("NO_NEW_VACANCIES"));
        assertTrue(empty.contains("No suitable or needs-review vacancies"));
        assertTrue(empty.contains("Backlog snapshot"));
        VacancyEvaluationDTO item = new VacancyEvaluationDTO(7, 8, "revision", "SUITABLE", 1, "PREFERRED",
                List.of("ROLE_MATCH"), List.of("SALARY_UNKNOWN"), List.of(), "https://example.org/jobs/7",
                null, "NONE", 0, "Java engineer");
        var withItem = new BatchPipelineResultDTO("QUIESCENT_SNAPSHOT", result("NO_NEW_VACANCIES").imported(),
                1, 1, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(item), List.of()));
        String text = formatter.format(date, profile, withItem);
        assertTrue(text.contains("https://example.org/jobs/7"));
        assertTrue(text.contains("ROLE_MATCH"));
        assertTrue(text.contains("SALARY_UNKNOWN"));
    }

    @Test
    void reportSizeBoundCountsUtf8AndOmitsLongEntries() {
        VacancyEvaluationDTO item = new VacancyEvaluationDTO(7, 8, "revision", "SUITABLE", 1, "PREFERRED",
                List.of("R".repeat(100)), List.of(), List.of(), "https://example.org/" + "a".repeat(800), null, "NONE", 0,
                "Ä".repeat(180));
        List<VacancyEvaluationDTO> items = java.util.Collections.nCopies(20, item);
        var base = result("BACKLOG_REMAINS");
        var full = new BatchPipelineResultDTO(base.outcome(), base.imported(), 0, 0, 0, 0, 0, base.outbox(),
                base.queue(), new ShortlistDTO(items, List.of()));
        String text = formatter.format(date, profile, full);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 10_000);
        assertTrue(text.matches("(?s).*Omitted entries due to message size: [1-9][0-9]*\\n"));
    }

    private static BatchPipelineResultDTO result(String outcome) {
        return new BatchPipelineResultDTO(outcome, new ActiveSourceImportResultDTO(1, 0, 0, 0, 0),
                0, 0, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(), List.of()));
    }
}
