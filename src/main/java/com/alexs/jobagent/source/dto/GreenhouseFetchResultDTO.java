package com.alexs.jobagent.source.dto;

import java.util.List;

public record GreenhouseFetchResultDTO(List<GreenhouseListingDTO> listings, int requests) {
}
