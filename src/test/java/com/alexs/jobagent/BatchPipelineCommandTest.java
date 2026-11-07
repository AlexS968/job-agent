package com.alexs.jobagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.service.BatchPipelineService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.outbox.dto.BacklogDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import com.alexs.jobagent.source.dto.ActiveSourceImportResultDTO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchPipelineCommandTest {
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final Environment environment = mock(Environment.class);
    private final ProfileFileService profiles = mock(ProfileFileService.class);
    private final BatchPipelineService service = mock(BatchPipelineService.class);
    private final LoadedProfileDTO profile = mock(LoadedProfileDTO.class);
    private final JobAgentApplication application = new JobAgentApplication();
    private static final String[] BOUNDED = {"--mode=batch-pipeline", "--max-events=50",
            "--duration-seconds=900", "--shortlist-limit=20"};

    @Test
    void explicitModePrintsBoundedSummaryAndExplainedShortlistWithoutProfile() {
        configure();
        var vacancy = new VacancyEvaluationDTO(34, 34, "revision", "NEEDS_REVIEW", 1, "PREFERRED",
                List.of(), List.of("SALARY_UNKNOWN"), List.of("Public source"),
                "https://example.org/jobs/34", null, "NONE", 0, "Backend Engineer");
        when(service.run(profile, 50, Duration.ofSeconds(900), 20)).thenReturn(result("BACKLOG_REMAINS",
                new ShortlistDTO(List.of(), List.of(vacancy))));
        Logger logger = (Logger) LoggerFactory.getLogger(JobAgentApplication.class);
        ListAppender<ILoggingEvent> output = new ListAppender<>();
        output.start();
        logger.addAppender(output);
        try {
            application.runBatchPipeline(BOUNDED, context, profiles);
        } finally {
            logger.detachAppender(output);
            output.stop();
        }
        String messages = output.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(messages.contains("Batch pipeline: outcome=BACKLOG_REMAINS"));
        assertTrue(messages.contains("NEEDS_REVIEW vacancy: id=34"));
        assertTrue(messages.contains("https://example.org/jobs/34"));
        assertTrue(messages.contains("SALARY_UNKNOWN"));
        assertFalse(messages.contains("private-profile-json"));
        verify(service).run(profile, 50, Duration.ofSeconds(900), 20);
    }

    @Test
    void invalidArgumentsDoNotLoadProfileOrStartWork() {
        String[] missing = {"--mode=batch-pipeline", "--max-events=50"};
        String[] unsupported = {"--mode=batch-pipeline", "--max-events=50",
                "--duration-seconds=900", "--shortlist-limit=20", "--unknown=yes"};
        assertThrows(IllegalArgumentException.class, () -> application.runBatchPipeline(missing, context, profiles));
        assertThrows(IllegalArgumentException.class, () -> application.runBatchPipeline(unsupported, context, profiles));
        verify(context, never()).getBean(BatchPipelineService.class);
    }

    @Test
    void failedAndTimedOutResultsProduceNonzeroBatchFailure() {
        configure();
        when(service.run(profile, 50, Duration.ofSeconds(900), 20)).thenReturn(
                result("FAILED", new ShortlistDTO(List.of(), List.of())),
                result("TIME_LIMIT", new ShortlistDTO(List.of(), List.of())));
        assertThrows(IllegalStateException.class, () -> application.runBatchPipeline(BOUNDED, context, profiles));
        assertThrows(IllegalStateException.class, () -> application.runBatchPipeline(BOUNDED, context, profiles));
        verify(service, org.mockito.Mockito.times(2)).run(profile, 50, Duration.ofSeconds(900), 20);
    }

    private void configure() {
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("JOBAGENT_EVALUATION_PROFILE_JSON")).thenReturn("private-profile-json");
        when(profiles.loadJson("private-profile-json")).thenReturn(profile);
        when(context.getBean(BatchPipelineService.class)).thenReturn(service);
    }

    private BatchPipelineResultDTO result(String outcome, ShortlistDTO shortlist) {
        return new BatchPipelineResultDTO(outcome, new ActiveSourceImportResultDTO(1, 0, 1, 0, 0),
                1, 1, 0, 0, 0, new BacklogDTO(1, 0, 0, 0, 0),
                new SqsQueueBacklogDTO(0, 0, 0), shortlist);
    }
}
