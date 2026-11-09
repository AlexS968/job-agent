package com.alexs.jobagent.outbox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

class SqsEventMapperTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MapperScan.class);

    @Test
    void mapsOnlyEnvelopeFieldsAndPreservesIdentity() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-11-09T12:00:00Z");
        OutboxEventEntity event = new OutboxEventEntity(eventId, "VacancyUpdated", 1, 42,
                occurredAt, 7, "{\"private\":true}", UUID.randomUUID(), 2);

        context.run(application -> {
            SqsEventMapper mapper = application.getBean(SqsEventMapper.class);
            assertEquals(new SqsEventDTO(eventId, "VacancyUpdated", 1, 42, occurredAt, 7),
                    mapper.toDTO(event));
            assertNull(mapper.toDTO(null));
        });
    }

    @Test
    void generatedMapperIsAvailableForConstructorInjection() {
        context.withBean(MapperConsumer.class).run(application ->
                assertSame(application.getBean(SqsEventMapper.class),
                        application.getBean(MapperConsumer.class).mapper));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = SqsEventMapper.class)
    static class MapperScan {
    }

    static class MapperConsumer {
        final SqsEventMapper mapper;

        MapperConsumer(SqsEventMapper mapper) {
            this.mapper = mapper;
        }
    }
}
