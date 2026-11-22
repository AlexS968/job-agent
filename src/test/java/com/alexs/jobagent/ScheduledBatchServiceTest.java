package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.batch.service.ScheduledBatchService;
import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.source.exception.GmailAccessException;
import com.alexs.jobagent.source.service.GmailCutoverService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScheduledBatchServiceTest {
    private static final Instant START = Instant.parse("2026-11-23T18:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 23);
    private static final Instant GMAIL_DEADLINE = START.plusSeconds(180);
    private final ScheduledBatchRepository repository = mock(ScheduledBatchRepository.class);
    private final BatchPipelineService pipeline = mock(BatchPipelineService.class);
    private final EvaluationService evaluations = mock(EvaluationService.class);
    private final GmailCutoverService gmail = mock(GmailCutoverService.class);
    private final LoadedProfileDTO de = profile("alex-de", "DE");
    private final LoadedProfileDTO at = profile("alex-at", "AT");
    private final ScheduledBatchService service = new ScheduledBatchService(
            repository, pipeline, evaluations, gmail, Clock.fixed(START, ZoneOffset.UTC));

    private static LoadedProfileDTO profile(String id, String country) {
        return new LoadedProfileDTO(new SearchProfileDTO(id, "v1", country,
                List.of("Java"), List.of(), List.of(), List.of(), List.of(), false, null),
                ("DE".equals(country) ? "a" : "b").repeat(64));
    }

    @BeforeEach
    void setUp() {
        when(repository.startInvocation(DATE)).thenReturn(true);
        when(gmail.runUntil(GMAIL_DEADLINE)).thenReturn(
                new GmailCutoverService.Result(true, false, false, 1, 0, 1, 0));
        when(pipeline.runWithoutShortlist(eq(de), eq(50), any(), eq(20))).thenReturn(emptyRun("NO_NEW_VACANCIES"));
        when(evaluations.evaluate(any(), eq(0L), eq(500), eq("rules-v2")))
                .thenReturn(new EvaluationBatchResultDTO(0, 0, 0, 0, false));
    }

    @Test
    void gmailPrecedesCollectionAndBothEvaluationSweeps() {
        service.run(List.of(de, at));
        var order = inOrder(gmail, pipeline, evaluations, repository);
        order.verify(gmail).runUntil(GMAIL_DEADLINE);
        order.verify(pipeline).runWithoutShortlist(eq(de), eq(50), any(), eq(20));
        order.verify(evaluations).evaluate(de, 0, 500, "rules-v2");
        order.verify(evaluations).evaluate(at, 0, 500, "rules-v2");
        order.verify(repository).finishInvocation(DATE, "COMPLETE");
    }

    @Test
    void recoverableGmailFailureDoesNotBlockOtherSourcesOrEvaluation() {
        when(gmail.runUntil(GMAIL_DEADLINE)).thenThrow(new GmailAccessException("Gmail request failed with HTTP 503"));
        service.run(List.of(de, at));
        verify(pipeline).runWithoutShortlist(eq(de), eq(50), any(), eq(20));
        verify(evaluations).evaluate(at, 0, 500, "rules-v2");
        verify(repository).finishInvocation(DATE, "COMPLETE");
    }

    @Test
    void gmailDatabaseFailureStopsAndMarksInvocationFailed() {
        when(gmail.runUntil(GMAIL_DEADLINE)).thenThrow(new DataAccessResourceFailureException("test"));
        var profiles = List.of(de, at);
        assertThrows(DataAccessResourceFailureException.class, () -> service.run(profiles));
        verifyNoInteractions(pipeline, evaluations);
        verify(repository).finishInvocation(DATE, "FAILED");
    }

    @Test
    void interruptionStopsAndPreservesInterruptFlag() {
        when(gmail.runUntil(GMAIL_DEADLINE)).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gmail interrupted");
        });
        var profiles = List.of(de, at);
        try {
            assertThrows(IllegalStateException.class, () -> service.run(profiles));
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(pipeline, evaluations);
            verify(repository).finishInvocation(DATE, "FAILED");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void completedDateSkipsAllWork() {
        when(repository.invocationStatus(DATE)).thenReturn("COMPLETE");
        service.run(List.of(de, at));
        verifyNoInteractions(gmail, pipeline, evaluations);
        verify(repository, never()).startInvocation(DATE);
    }

    @Test
    void invalidProfilesAndAmbientTransactionStopBeforeWork() {
        var reversedProfiles = List.of(at, de);
        var profiles = List.of(de, at);
        assertThrows(IllegalArgumentException.class, () -> service.run(reversedProfiles));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.run(profiles));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(repository, never()).startInvocation(any());
    }

    private static BatchPipelineResultDTO emptyRun(String outcome) {
        return new BatchPipelineResultDTO(outcome, new ActiveSourceImportResultDTO(0, 0, 0, 0, 0),
                0, 0, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(), List.of()));
    }
}
