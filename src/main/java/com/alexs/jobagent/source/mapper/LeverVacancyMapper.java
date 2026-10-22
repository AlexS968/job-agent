package com.alexs.jobagent.source.mapper;

import com.alexs.jobagent.config.MappingConfig;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.vacancy.dto.EmployerDTO;
import com.alexs.jobagent.vacancy.dto.VacancyDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MappingConfig.class)
public interface LeverVacancyMapper {
    @Mapping(target = "listingId", source = "listing.id")
    @Mapping(target = "title", source = "listing.title")
    @Mapping(target = "location", source = "listing.location")
    @Mapping(target = "sourceNamespace", source = "namespace")
    @Mapping(target = "employer", source = "employer")
    VacancyDTO toVacancy(LeverListingDTO listing, String namespace, EmployerDTO employer);
}
