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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

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
}
