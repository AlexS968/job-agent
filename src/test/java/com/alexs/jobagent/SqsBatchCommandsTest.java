package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.alexs.jobagent.outbox.dto.SqsBatchResultDTO;
import com.alexs.jobagent.outbox.dto.SqsStatusDTO;
import com.alexs.jobagent.outbox.service.SqsConsumerService;
import com.alexs.jobagent.outbox.service.SqsProducerService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.outbox.dto.SqsEvaluationBatchDTO;
import com.alexs.jobagent.outbox.dto.SqsEvaluationStatusDTO;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.SearchProfileDTO;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

class SqsBatchCommandsTest {
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final SqsProducerService producer = mock(SqsProducerService.class);
    private final SqsConsumerService consumer = mock(SqsConsumerService.class);
    private final SqsBatchCommands commands = new SqsBatchCommands(context);

    @Test
    void producerUsesBoundedOptionAndReportsDeferredOrFailedWork() {
        when(context.getBean(SqsProducerService.class)).thenReturn(producer);
        when(producer.publish(2)).thenReturn(new SqsBatchResultDTO(1, 0, 1, 0));
        assertThrows(IllegalStateException.class,
                () -> commands.produce(new String[] {"--mode=sqs-produce", "--limit=2"}));
        verify(producer).publish(2);
        when(producer.publish(20)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 1));
        assertThrows(IllegalStateException.class, () -> commands.produce(new String[] {"--mode=sqs-produce"}));
        verify(producer).publish(20);
        when(producer.publish(1)).thenReturn(new SqsBatchResultDTO(1, 0, 0, 0));
        assertDoesNotThrow(() -> commands.produce(new String[] {"--limit=1", "--mode=sqs-produce"}));
    }

    @Test
    void consumerReportsRetryableMessagesAndAcceptsDuplicateReceipts() {
        when(context.getBean(SqsConsumerService.class)).thenReturn(consumer);
        when(consumer.consume(1)).thenReturn(new SqsBatchResultDTO(0, 0, 0, 1));
        assertThrows(IllegalStateException.class, () -> commands.consume(new String[] {"--mode=sqs-consume"}));
        when(consumer.consume(2)).thenReturn(new SqsBatchResultDTO(0, 2, 0, 0));
        assertDoesNotThrow(() -> commands.consume(new String[] {"--mode=sqs-consume", "--limit=2"}));
        verify(consumer).consume(2);
    }

    @Test
    void invalidArgumentsNeverResolveQueueBeans() {
        assertThrows(IllegalArgumentException.class,
                () -> commands.produce(new String[] {"--mode=sqs-produce", "--limit=1", "--limit=2"}));
        assertThrows(IllegalArgumentException.class,
                () -> commands.consume(new String[] {"--mode=sqs-consume", "--unknown=1"}));
        assertThrows(IllegalArgumentException.class,
                () -> commands.status(new String[] {"--mode=sqs-status", "--event-id=bad"}));
        verify(context, never()).getBean(SqsProducerService.class);
        verify(context, never()).getBean(SqsConsumerService.class);
    }

    @Test
    void statusReadsOnlyTheRequestedEvent() {
        UUID eventId = UUID.randomUUID();
        when(context.getBean(SqsConsumerService.class)).thenReturn(consumer);
        when(consumer.status(eventId)).thenReturn(new SqsStatusDTO(true, true));
        commands.status(new String[] {"--mode=sqs-status", "--event-id=" + eventId});
        verify(consumer).status(eventId);
    }

    @Test
    void explicitEvaluationModeUsesConfiguredProfileAndFailsOnRetryableWork() {
        var environment = mock(Environment.class);
        var profiles = mock(ProfileFileService.class);
        var evaluator = mock(SqsEvaluationConsumerService.class);
        var selected = new LoadedProfileDTO(new SearchProfileDTO("synthetic", "v1", "DE",
                List.of("Backend"), List.of(), List.of(), List.of(), List.of(), false, null), "a".repeat(64));
        when(context.getEnvironment()).thenReturn(environment);
        when(environment.getProperty("JOBAGENT_EVALUATION_PROFILE_JSON")).thenReturn("synthetic-profile-json");
        when(context.getBean(ProfileFileService.class)).thenReturn(profiles);
        when(profiles.loadJson("synthetic-profile-json")).thenReturn(selected);
        when(context.getBean(SqsEvaluationConsumerService.class)).thenReturn(evaluator);
        when(evaluator.consume(1, selected)).thenReturn(new SqsEvaluationBatchDTO(1, 0, 0, 0));
        assertDoesNotThrow(() -> commands.evaluate(new String[] {"--mode=sqs-evaluate"}));
        when(evaluator.consume(1, selected)).thenReturn(new SqsEvaluationBatchDTO(0, 0, 0, 1));
        assertThrows(IllegalStateException.class, () -> commands.evaluate(new String[] {"--mode=sqs-evaluate"}));
        UUID eventId = UUID.randomUUID();
        when(evaluator.status(eventId, selected)).thenReturn(new SqsEvaluationStatusDTO("STALE", null));
        assertDoesNotThrow(() -> commands.evaluationStatus(new String[] {
                "--mode=sqs-evaluation-status", "--event-id=" + eventId}));
        verify(evaluator).status(eventId, selected);
    }
}
