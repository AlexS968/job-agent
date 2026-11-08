package com.alexs.jobagent;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.batch.service.BatchReportFormatter;
import com.alexs.jobagent.batch.service.ReportGateway;
import com.alexs.jobagent.batch.service.ScheduledBatchService;
import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.service.EvaluationService;
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
    private final EvaluationService evaluations = mock(EvaluationService.class);
    private final ReportGateway gateway = mock(ReportGateway.class);
    private final BatchReportFormatter formatter = new BatchReportFormatter();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T18:00:00Z"), ZoneOffset.UTC);
    private final LocalDate date = LocalDate.of(2026, 11, 5);
    private final LoadedProfileDTO de = profile("alex-de", "DE");
    private final LoadedProfileDTO at = profile("alex-at", "AT");
    private final LoadedProfileDTO profile = de;

    private static LoadedProfileDTO profile(String id, String country) {
        return new LoadedProfileDTO(new SearchProfileDTO(id, "v1", country,
                List.of("Java"), List.of(), List.of(), List.of(), List.of(), false, null),
                ("DE".equals(country) ? "a" : "b").repeat(64));
    }

    private ScheduledBatchService service() {
        return new ScheduledBatchService(repository, pipeline, evaluations, formatter, gateway, clock);
    }

    private void successfulDependencies() {
        when(repository.startInvocation(date)).thenReturn(true);
        when(repository.start(date, "alex-de", "v1", "a".repeat(64))).thenReturn(true);
        when(repository.start(date, "alex-at", "v1", "b".repeat(64))).thenReturn(true);
        when(pipeline.runWithoutShortlist(eq(de), eq(50), any(), eq(20))).thenReturn(result("NO_NEW_VACANCIES"));
        when(pipeline.snapshot(eq(de), eq(20))).thenReturn(result("NO_NEW_VACANCIES"));
        when(pipeline.snapshot(eq(at), eq(20))).thenReturn(result("EVALUATION_SWEEP"));
        when(evaluations.evaluate(any(), eq(0L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(0, 0, 0, 0, false));
        when(gateway.publish(any(), any())).thenReturn("message-id");
    }

    @Test
    void twoReportsUseDistinctProfilesAfterOneImportAndIndependentSweeps() {
        successfulDependencies();
        service().run(List.of(de, at));
        verify(pipeline).runWithoutShortlist(eq(de), eq(50), any(), eq(20));
        verify(evaluations).evaluate(eq(de), eq(0L), eq(500), eq("rules-v1"));
        verify(evaluations).evaluate(eq(at), eq(0L), eq(500), eq("rules-v1"));
        var order = org.mockito.Mockito.inOrder(evaluations, pipeline);
        order.verify(evaluations).evaluate(eq(de), eq(0L), eq(500), eq("rules-v1"));
        order.verify(evaluations).evaluate(eq(at), eq(0L), eq(500), eq("rules-v1"));
        order.verify(pipeline).snapshot(eq(de), eq(20));
        verify(gateway).publish(eq("Job Agent Germany 2026-11-05: no relevant opportunities"),
                org.mockito.ArgumentMatchers.contains("opportunities for Germany"));
        verify(gateway).publish(eq("Job Agent Austria 2026-11-05: no relevant opportunities"),
                org.mockito.ArgumentMatchers.contains("opportunities for Austria"));
        verify(repository).finishInvocation(date, "COMPLETE");
    }

    @Test
    void pagedEvaluationCoversExistingVacanciesForBothProfilesEvenWithEmptyQueue() {
        successfulDependencies();
        when(evaluations.evaluate(eq(de), eq(0L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(500, 500, 0, 500, true));
        when(evaluations.evaluate(eq(de), eq(500L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(1, 1, 0, 501, false));
        when(evaluations.evaluate(eq(at), eq(0L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(1, 1, 0, 1, false));
        service().run(List.of(de, at));
        verify(evaluations).evaluate(eq(de), eq(500L), eq(500), eq("rules-v1"));
        verify(evaluations).evaluate(eq(at), eq(0L), eq(500), eq("rules-v1"));
        verify(gateway).publish(org.mockito.ArgumentMatchers.contains("Austria"),
                org.mockito.ArgumentMatchers.argThat(body -> body.contains("Evaluations stored: 1")
                        && body.contains("Queue: 0 sent; 0 duplicate deliveries")
                        && !body.matches("(?s).*Queue:[^\\n]*evaluat.*")));
    }

    @Test
    void secondProfileEvaluationFailureStopsBeforeEitherPublication() {
        successfulDependencies();
        when(evaluations.evaluate(eq(at), eq(0L), eq(500), eq("rules-v1")))
                .thenThrow(new IllegalStateException("synthetic evaluation failure"));
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(gateway, never()).publish(any(), any());
        verify(repository).finishInvocation(date, "FAILED");
    }

    @Test
    void futureProfileVersionsAreAcceptedWithoutChangingReviewedIdsOrCountries() {
        LoadedProfileDTO deV2 = new LoadedProfileDTO(new SearchProfileDTO("alex-de", "v2", "DE",
                List.of("Senior Java"), List.of(), List.of(), List.of(), List.of(), false, null), "c".repeat(64));
        LoadedProfileDTO atV2 = new LoadedProfileDTO(new SearchProfileDTO("alex-at", "v2", "AT",
                List.of("Senior Java"), List.of(), List.of(), List.of(), List.of(), false, null), "d".repeat(64));
        when(repository.startInvocation(date)).thenReturn(true);
        when(repository.start(date, "alex-de", "v2", "c".repeat(64))).thenReturn(true);
        when(repository.start(date, "alex-at", "v2", "d".repeat(64))).thenReturn(true);
        when(pipeline.runWithoutShortlist(eq(deV2), eq(50), any(), eq(20)))
                .thenReturn(result("NO_NEW_VACANCIES"));
        when(pipeline.snapshot(any(), eq(20))).thenReturn(result("EVALUATION_SWEEP"));
        when(evaluations.evaluate(any(), eq(0L), eq(500), eq("rules-v1")))
                .thenReturn(new EvaluationBatchResultDTO(0, 0, 0, 0, false));
        when(gateway.publish(any(), any())).thenReturn("synthetic-message-id");
        service().run(List.of(deV2, atV2));
        verify(gateway).publish(org.mockito.ArgumentMatchers.contains("Germany"),
                org.mockito.ArgumentMatchers.contains("opportunities for Germany"));
        verify(gateway).publish(org.mockito.ArgumentMatchers.contains("Austria"),
                org.mockito.ArgumentMatchers.contains("opportunities for Austria"));
    }

    @Test
    void duplicateCompletedDateDoesNotRepeatImportOrEmail() {
        when(repository.invocationStatus(date)).thenReturn("COMPLETE");
        service().run(List.of(de, at));
        verify(pipeline, never()).runWithoutShortlist(any(), any(Integer.class), any(), any(Integer.class));
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void publishedFirstProfileIsNotReplayedOnSecondProfileRestart() {
        successfulDependencies();
        when(repository.profileStatus(date, "alex-de", "v1", "a".repeat(64))).thenReturn("PUBLISHED");
        when(repository.profileStatus(date, "alex-at", "v1", "b".repeat(64))).thenReturn("FAILED");
        when(repository.retryFailedProfile(date, "alex-at", "v1", "b".repeat(64))).thenReturn(true);
        service().run(List.of(de, at));
        verify(pipeline, never()).runWithoutShortlist(any(), any(Integer.class), any(), any(Integer.class));
        verify(gateway, never()).publish(org.mockito.ArgumentMatchers.contains("Germany"), any());
        verify(gateway).publish(org.mockito.ArgumentMatchers.contains("Austria"), any());
    }

    @Test
    void ambiguousSecondPublicationNeverReplaysFirstAndFailsInvocation() {
        successfulDependencies();
        when(gateway.publish(org.mockito.ArgumentMatchers.contains("Austria"), any()))
                .thenThrow(new IllegalStateException("synthetic timeout"));
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(repository).transition(date, "alex-de", "PUBLISHING", "PUBLISHED", null, "message-id");
        verify(repository).transition(date, "alex-at", "PUBLISHING", "UNCERTAIN", null, null);
        verify(repository).finishInvocation(date, "FAILED");
    }

    @Test
    void failedFirstPipelineSendsNeitherEmail() {
        successfulDependencies();
        when(pipeline.runWithoutShortlist(eq(de), eq(50), any(), eq(20))).thenReturn(result("FAILED"));
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(gateway, never()).publish(any(), any());
        verify(repository).finishInvocation(date, "FAILED");
    }

    @Test
    void missingMessageIdentityIsUncertain() {
        successfulDependencies();
        when(gateway.publish(org.mockito.ArgumentMatchers.contains("Austria"), any())).thenReturn("");
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(repository).transition(date, "alex-at", "PUBLISHING", "UNCERTAIN", null, null);
    }

    @Test
    void lostDatabaseAcknowledgementRemainsPublishingForReview() {
        successfulDependencies();
        doThrow(new IllegalStateException("synthetic database outage")).when(repository)
                .transition(date, "alex-at", "PUBLISHING", "PUBLISHED", null, "message-id");
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(repository).transition(date, "alex-at", "PUBLISHING", "PUBLISHED", null, "message-id");
        verify(repository).transition(date, "alex-at", "RUNNING", "FAILED", null, null);
    }

    @Test
    void ambientTransactionAndWrongProfileStopBeforeReservation() {
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalArgumentException.class, () -> scheduled.run(List.of(at, de)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(repository, never()).startInvocation(any());
    }

    @Test
    void activeOrUncertainDateRequiresReview() {
        ScheduledBatchService scheduled = service();
        assertThrows(IllegalStateException.class, () -> scheduled.run(List.of(de, at)));
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void reportContainsBoundedEntriesAndExplicitEmptyResult() {
        String empty = formatter.format(date, profile, result("NO_NEW_VACANCIES"));
        assertTrue(empty.contains("No relevant opportunities found"));
        assertTrue(empty.contains("Run diagnostics"));
        assertEquals("Job Agent Germany 2026-11-05: no relevant opportunities",
                formatter.subject(date, de, result("NO_NEW_VACANCIES")));
        assertEquals("Job Agent Austria 2026-11-05: no relevant opportunities",
                formatter.subject(date, at, result("NO_NEW_VACANCIES")));
        VacancyEvaluationDTO item = new VacancyEvaluationDTO(7, 8, "revision", "SUITABLE", 1, "PREFERRED",
                List.of("ROLE_MATCH"), List.of("SALARY_UNKNOWN"), List.of(), "https://example.org/jobs/7",
                null, "NONE", 0, "Java engineer");
        var withItem = new BatchPipelineResultDTO("QUIESCENT_SNAPSHOT", result("NO_NEW_VACANCIES").imported(),
                1, 1, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(item), List.of()));
        String text = formatter.format(date, profile, withItem);
        assertTrue(text.contains("https://example.org/jobs/7"));
        assertTrue(text.contains("preferred role title"));
        assertTrue(text.contains("salary and its terms"));
        assertFalse(text.contains("SALARY_UNKNOWN"));
        assertFalse(text.contains("QUIESCENT_SNAPSHOT"));
        assertEquals("Job Agent Germany 2026-11-05: 1 suitable, 0 to review",
                formatter.subject(date, de, withItem));
    }

    @Test
    void reportCountsRelevantEntriesOmittedByShortlistLimit() {
        VacancyEvaluationDTO item = new VacancyEvaluationDTO(7, 8, "revision", "NEEDS_REVIEW", 9, "RESERVE",
                List.of("RESERVE_ROLE"), List.of("WORK_ELIGIBILITY_UNCONFIRMED", "SALARY_UNKNOWN"), List.of(),
                "https://example.org/jobs/7", null, "NONE", 0, "Java engineer");
        var base = result("EVALUATION_SWEEP");
        var report = new BatchPipelineResultDTO(base.outcome(), base.imported(), 0, 0, 0, 0, 0,
                base.outbox(), base.queue(), new ShortlistDTO(List.of(), List.of(item), 0, 25));
        String text = formatter.format(date, de, report);
        assertTrue(text.contains("Roles needing review: 25"));
        assertTrue(text.contains("24 relevant opportunities omitted"));
        assertTrue(text.contains("work-country permission; salary and its terms"));
        assertFalse(text.contains("WORK_ELIGIBILITY_UNCONFIRMED"));
    }

    @Test
    void formatterNeverRendersMoreThanTwentyEvenIfCallerSuppliesMore() {
        VacancyEvaluationDTO item = new VacancyEvaluationDTO(7, 8, "revision", "SUITABLE", 1, "PREFERRED",
                List.of("PREFERRED_ROLE"), List.of(), List.of(), "https://example.org/jobs/7",
                null, "NONE", 0, "Backend Engineer");
        var base = result("EVALUATION_SWEEP");
        var report = new BatchPipelineResultDTO(base.outcome(), base.imported(), 0, 0, 0, 0, 0,
                base.outbox(), base.queue(), new ShortlistDTO(java.util.Collections.nCopies(21, item), List.of()));
        String text = formatter.format(date, de, report);
        assertEquals(20, text.split("Suitable: Backend Engineer", -1).length - 1);
        assertTrue(text.contains("1 relevant opportunities omitted"));
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
        assertTrue(text.matches("(?s).*[1-9][0-9]* relevant opportunities omitted.*"));
    }

    @Test
    void reportRejectsUnsafeLinksAndSanitizesUntrustedText() {
        var base = result("BACKLOG_REMAINS");
        VacancyEvaluationDTO unsafe = new VacancyEvaluationDTO(8, 9, "revision", "NEEDS_REVIEW", 2, "RESERVE",
                null, List.of(), List.of(), "https://user:secret@example.org/job", null, "NONE", 0,
                "Backend\r\nHidden header");
        VacancyEvaluationDTO malformed = new VacancyEvaluationDTO(9, 10, "revision", "NEEDS_REVIEW", 2, "RESERVE",
                List.of(), null, List.of(), "https://example.org/%", null, "NONE", 0, null);
        VacancyEvaluationDTO nonHttps = new VacancyEvaluationDTO(10, 11, "revision", "NEEDS_REVIEW", 2, "RESERVE",
                List.of(), List.of(), List.of(), "http://example.org/job", null, "NONE", 0, "Java");
        VacancyEvaluationDTO tooLong = new VacancyEvaluationDTO(11, 12, "revision", "NEEDS_REVIEW", 2, "RESERVE",
                List.of(), List.of(), List.of(), "https://example.org/" + "x".repeat(1000), null, "NONE", 0,
                "Java");
        var withItems = new BatchPipelineResultDTO(base.outcome(), base.imported(), 0, 0, 0, 0, 0, base.outbox(),
                base.queue(), new ShortlistDTO(List.of(), List.of(unsafe, malformed, nonHttps, tooLong)));
        String text = formatter.format(date, profile, withItems);
        assertTrue(text.contains("Needs review: Backend  Hidden header"));
        assertFalse(text.contains("user:secret"));
        assertFalse(text.contains("http://example.org/job"));
        assertTrue(text.contains("Needs review: unknown"));
        assertTrue(text.contains("[public link unavailable]"));
    }

    private static BatchPipelineResultDTO result(String outcome) {
        return new BatchPipelineResultDTO(outcome, new ActiveSourceImportResultDTO(1, 0, 0, 0, 0),
                0, 0, 0, 0, 0, new BacklogDTO(0, 0, 0, 0, 0), new SqsQueueBacklogDTO(0, 0, 0),
                new ShortlistDTO(List.of(), List.of()));
    }
}
