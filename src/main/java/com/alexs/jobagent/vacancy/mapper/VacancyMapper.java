package com.alexs.jobagent.vacancy.mapper;

import com.alexs.jobagent.config.MappingConfig;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import com.alexs.jobagent.vacancy.entity.EmployerEntity;
import com.alexs.jobagent.vacancy.entity.VacancyEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MappingConfig.class)
public interface VacancyMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "externalId", source = "employerId")
    EmployerEntity toEntity(EmployerDTO employer);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "employerDbId", source = "employerDbId")
    VacancyEntity toEntity(VacancyDTO vacancy, long employerDbId);
}
