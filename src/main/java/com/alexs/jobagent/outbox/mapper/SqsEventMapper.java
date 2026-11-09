package com.alexs.jobagent.outbox.mapper;

import com.alexs.jobagent.outbox.dto.SqsEventDTO;
import com.alexs.jobagent.outbox.entity.OutboxEventEntity;
import com.alexs.jobagent.config.MappingConfig;
import org.mapstruct.Mapper;

@Mapper(config = MappingConfig.class)
public interface SqsEventMapper {
    SqsEventDTO toDTO(OutboxEventEntity event);
}
