package com.alexs.jobagent;

import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsBatchResultDTO;
import com.alexs.jobagent.outbox.dto.SqsEvaluationBatchDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.outbox.service.OutboxService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.outbox.service.SqsGateway;
import com.alexs.jobagent.outbox.service.SqsProducerService;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import com.alexs.jobagent.source.service.SourceRegistryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchPipelineServiceTest {
    private final SourceRegistryService sources = mock(SourceRegistryService.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final SqsProducerService producer = mock(SqsProducerService.class);
    private final SqsEvaluationConsumerService consumer = mock(SqsEvaluationConsumerService.class);
    private final SqsGateway queue = mock(SqsGateway.class);
    private final EvaluationService evaluations = mock(EvaluationService.class);
    private final LoadedProfileDTO profile = mock(LoadedProfileDTO.class);
    private BatchPipelineService pipeline;

    @BeforeEach
    void setup() {
        pipeline = new BatchPipelineService(sources, outbox, producer, consumer, queue, evaluations,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        when(outbox.backlog()).thenReturn(new BacklogDTO(0, 0, 0, 0, 0));
        when(queue.backlog()).thenReturn(new SqsQueueBacklogDTO(0, 0, 0));
        when(evaluations.shortlist(any(), anyInt(), any())).thenReturn(new ShortlistDTO(List.of(), List.of()));
    }

    @Test
    void noNewVacanciesStillInspectsExistingWorkAndReportsSnapshot() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 0, 0, 18));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 0));
        when(consumer.consume(10, profile)).thenReturn(new SqsEvaluationBatchDTO(0, 0, 0, 0));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("NO_NEW_VACANCIES", result.outcome());
        assertEquals(18, result.imported().unchanged());
        verify(consumer).consume(10, profile);
    }

    @Test
    void secondProfileSnapshotDoesNotImportOrConsumeQueue() {
        when(outbox.backlog()).thenReturn(new BacklogDTO(0, 1, 0, 0, 0));
        var result = pipeline.snapshot(profile, 20);
        assertEquals("BACKLOG_REMAINS", result.outcome());
        assertEquals(0, result.imported().sources());
        verify(sources, never()).importActive();
        verify(producer, never()).publish(anyInt());
        verify(consumer, never()).consume(anyInt(), any());
    }

    @Test
    void scheduledProcessingDefersShortlistUntilProfilesAreRegistered() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(0, 0, 0, 0, 0));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 0));
        when(consumer.consume(10, profile)).thenReturn(new SqsEvaluationBatchDTO(0, 0, 0, 0));
        var processed = pipeline.runWithoutShortlist(profile, 10, Duration.ofMinutes(10), 20);
        assertEquals("NO_NEW_VACANCIES", processed.outcome());
        verify(evaluations, never()).shortlist(any(), anyInt(), any());
        var snapshot = pipeline.snapshot(profile, 20);
        assertEquals("EVALUATION_SWEEP", snapshot.outcome());
    }

    @Test
    void emptyReceiveDoesNotHideFutureAndInflightWork() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 2, 1, 3));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(2, 0, 0, 0));
        when(producer.publish(8)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 0));
        when(consumer.consume(10, profile)).thenReturn(new SqsEvaluationBatchDTO(1, 0, 1, 0));
        when(consumer.consume(8, profile)).thenReturn(new SqsEvaluationBatchDTO(0, 0, 0, 0));
        when(outbox.backlog()).thenReturn(new BacklogDTO(0, 1, 0, 0, 0));
        when(queue.backlog()).thenReturn(new SqsQueueBacklogDTO(0, 1, 0));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("BACKLOG_REMAINS", result.outcome());
        assertEquals(1, result.stale());
        assertEquals(2, result.published());
        assertEquals(1, result.queue().inFlight());
    }

    @Test
    void failedImportStopsBeforePublishingOrConsuming() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 1, 1, 0, 0));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("FAILED", result.outcome());
        verify(producer, never()).publish(anyInt());
        verify(consumer, never()).consume(anyInt(), any());
    }

    @Test
    void uncertainPublicationStopsBeforeQueueConsumption() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 1, 0, 0));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(0, 0, 1, 0));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("FAILED", result.outcome());
        assertEquals(1, result.failed());
        verify(consumer, never()).consume(anyInt(), any());
    }

    @Test
    void failedQueueEvaluationStopsNextBatchAndPreservesCommittedCounts() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 1, 0, 0));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 0));
        when(consumer.consume(10, profile)).thenReturn(new SqsEvaluationBatchDTO(1, 0, 0, 1));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("FAILED", result.outcome());
        assertEquals(1, result.evaluated());
        assertEquals(1, result.failed());
        verify(consumer).consume(10, profile);
    }

    @Test
    void committedEventBudgetStopsPublicationWithoutAnotherClaim() {
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 10, 0, 0));
        when(producer.publish(10)).thenReturn(new SqsBatchResultDTO(10, 0, 0, 0));
        when(consumer.consume(10, profile)).thenReturn(new SqsEvaluationBatchDTO(10, 0, 0, 0));
        var result = pipeline.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals(10, result.published());
        assertEquals(10, result.evaluated());
        verify(producer).publish(10);
        verify(consumer).consume(10, profile);
    }

    @Test
    void rejectsAmbientTransactionBeforeAnySourceOrQueueWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            Duration duration = Duration.ofMinutes(10);
            assertThrows(IllegalStateException.class, () -> pipeline.run(profile, 10, duration, 5));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(sources, never()).importActive();
        verify(producer, never()).publish(anyInt());
    }

    @Test
    void elapsedBudgetAfterImportStopsBeforePublication() {
        Clock advancing = mock(Clock.class);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        when(advancing.instant()).thenReturn(start, start.plusSeconds(901), start.plusSeconds(901));
        var bounded = new BatchPipelineService(sources, outbox, producer, consumer, queue, evaluations, advancing);
        when(sources.importActive()).thenReturn(new ActiveSourceImportResultDTO(1, 0, 1, 0, 0));
        var result = bounded.run(profile, 10, Duration.ofMinutes(10), 5);
        assertEquals("TIME_LIMIT", result.outcome());
        verify(producer, never()).publish(anyInt());
    }

    @Test
    void boundsRejectWorkBeforeImport() {
        Duration permitted = Duration.ofMinutes(10);
        Duration tooLong = Duration.ofMinutes(16);
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.run(profile, 101, permitted, 5));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.run(profile, 10, tooLong, 5));
        verify(sources, never()).importActive();
    }
}
