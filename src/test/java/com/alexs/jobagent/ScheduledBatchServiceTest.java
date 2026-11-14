package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.dto.PreparedResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.batch.service.BatchReportFormatter;
import com.alexs.jobagent.batch.service.ReportGateway;
import com.alexs.jobagent.batch.service.ScheduledBatchService;
import com.alexs.jobagent.batch.service.ScheduledResultService;
import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScheduledBatchServiceTest {
    private final ScheduledBatchRepository repository = mock(ScheduledBatchRepository.class);
    private final ScheduledResultService results = mock(ScheduledResultService.class);
    private final BatchPipelineService pipeline = mock(BatchPipelineService.class);
    private final EvaluationService evaluations = mock(EvaluationService.class);
    private final ReportGateway gateway = mock(ReportGateway.class);
    private final BatchReportFormatter formatter = new BatchReportFormatter();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T18:00:00Z"), ZoneOffset.UTC);
    private final LocalDate date = LocalDate.of(2026, 11, 5);
    private final LoadedProfileDTO de = profile("alex-de", "DE");
    private final LoadedProfileDTO at = profile("alex-at", "AT");
    private final PreparedResultDTO prepared = new PreparedResultDTO(
            "Job Agent 2026-11-05: 0 new opportunities", "synthetic safe result", 0, 0, 0, 0, 0, 0);

    private static LoadedProfileDTO profile(String id, String country) {
        return new LoadedProfileDTO(new SearchProfileDTO(id, "v1", country,
                List.of("Java"), List.of(), List.of(), List.of(), List.of(), false, null),
                ("DE".equals(country) ? "a" : "b").repeat(64));
    }

    private ScheduledBatchService service() {
        return new ScheduledBatchService(repository, results, pipeline, evaluations, formatter, gateway, clock,
                "https://page.example.test/");
    }

    private void successfulDependencies() {
        when(repository.startInvocation(date)).thenReturn(true);
        when(pipeline.runWithoutShortlist(eq(de), eq(50), any(), eq(20))).thenReturn(emptyRun("NO_NEW_VACANCIES"));
        when(evaluations.evaluate(any(), eq(0L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(0, 0, 0, 0, false));
        when(evaluations.reportCandidates(any(), eq("rules-v1"))).thenReturn(List.of());
        when(results.prepare(eq(date), eq(de), eq(at), any(), any(),
                eq("https://page.example.test/"), any())).thenReturn(prepared);
        when(gateway.publish(any(), any())).thenReturn("synthetic-message-id");
    }

    @Test
    void oneImportTwoSweepsAndOneResultPublication() {
        successfulDependencies();
        service().run(List.of(de, at));
        verify(pipeline).runWithoutShortlist(eq(de), eq(50), any(), eq(20));
        verify(evaluations).evaluate(eq(de), eq(0L), eq(500), eq("rules-v1"));
        verify(evaluations).evaluate(eq(at), eq(0L), eq(500), eq("rules-v1"));
        verify(evaluations).reportCandidates(de, "rules-v1");
        verify(evaluations).reportCandidates(at, "rules-v1");
        verify(results).markPublishing(date);
        verify(gateway).publish(prepared.subject(), prepared.body());
        verify(results).markPublished(date, "synthetic-message-id");
        verify(repository).finishInvocation(date, "COMPLETE");
    }

    @Test
    void secondSweepFailureDoesNotPrepareOrPublish() {
        successfulDependencies();
        when(evaluations.evaluate(eq(at), eq(0L), eq(500), eq("rules-v1")))
                .thenThrow(new IllegalStateException("synthetic evaluation failure"));
        assertThrows(IllegalStateException.class, () -> service().run(List.of(de, at)));
        verify(results, never()).prepare(any(), any(), any(), any(), any(), any(), any());
        verify(gateway, never()).publish(any(), any());
        verify(repository).finishInvocation(date, "FAILED");
    }

    @Test
    void completedDateAndLegacyDateNeverRepublish() {
        when(repository.invocationStatus(date)).thenReturn("COMPLETE");
        service().run(List.of(de, at));
        verify(gateway, never()).publish(any(), any());
        when(repository.invocationStatus(date)).thenReturn(null);
        when(repository.hasLegacyReportOn(date)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service().run(List.of(de, at)));
        verify(repository, never()).startInvocation(date);
    }

    @Test
    void ambiguousSnsOutcomeIsFencedAndDoesNotReplay() {
        successfulDependencies();
        when(gateway.publish(any(), any())).thenThrow(new IllegalStateException("synthetic timeout"));
        assertThrows(IllegalStateException.class, () -> service().run(List.of(de, at)));
        verify(results).markUncertain(date);
        verify(repository).finishInvocation(date, "FAILED");
        verify(gateway).publish(any(), any());
    }

    @Test
    void lostDatabaseAcknowledgementLeavesPublishingAndFailsInvocation() {
        successfulDependencies();
        doThrow(new IllegalStateException("synthetic database outage")).when(results)
                .markPublished(date, "synthetic-message-id");
        assertThrows(IllegalStateException.class, () -> service().run(List.of(de, at)));
        verify(results, never()).markUncertain(date);
        verify(repository).finishInvocation(date, "FAILED");
    }

    @Test
    void publishedResultWithFailedInvocationCompletesWithoutAnotherSnsCall() {
        successfulDependencies();
        when(repository.resultStatus(date)).thenReturn("PUBLISHED");
        service().run(List.of(de, at));
        verify(gateway, never()).publish(any(), any());
        verify(repository).finishInvocation(date, "COMPLETE");
    }

    @Test
    void invalidProfileOrMissingPageStopsBeforeReservation() {
        assertThrows(IllegalArgumentException.class, () -> service().run(List.of(at, de)));
        var noPage = new ScheduledBatchService(repository, results, pipeline, evaluations, formatter, gateway,
                clock, "");
        assertThrows(IllegalArgumentException.class, () -> noPage.run(List.of(de, at)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service().run(List.of(de, at)));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(repository, never()).startInvocation(any());
    }

    @Test
    void combinedFormatterIsShortSafeAndExplainsOverlapAndZero() {
        var zero = formatter.combined(date, 0, 0, 0, 0, 0, 0, false, false,
                "https://page.example.test/", emptyRun("NO_NEW_VACANCIES"));
        assertEquals("Job Agent 2026-11-05: 0 new opportunities", zero.subject());
        org.junit.jupiter.api.Assertions.assertTrue(zero.body().contains("No newly stored opportunities"));
        org.junit.jupiter.api.Assertions.assertTrue(zero.body().contains("Germany: 0 new; 0 newly relevant"));
        org.junit.jupiter.api.Assertions.assertTrue(zero.body().contains("Austria: 0 new; 0 newly relevant"));
        var shared = formatter.combined(date, 1, 1, 1, 0, 0, 0, false, false,
                "https://page.example.test/", emptyRun("QUIESCENT_SNAPSHOT"));
        assertEquals("Job Agent 2026-11-05: 1 new opportunity", shared.subject());
        org.junit.jupiter.api.Assertions.assertTrue(shared.body().contains("A vacancy may count for both"));
        org.junit.jupiter.api.Assertions.assertTrue(shared.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4000);
        assertThrows(IllegalArgumentException.class, () -> formatter.combined(date, 0, 0, 0, 0, 0, 0, false, false,
                "https://user:secret@page.example.test/", emptyRun("NO_NEW_VACANCIES")));
    }

    private static BatchPipelineResultDTO emptyRun(String outcome) {
        return new BatchPipelineResultDTO(outcome, new ActiveSourceImportResultDTO(0, 0, 0, 0, 0),
                0, 0, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(), List.of()));
    }
}
