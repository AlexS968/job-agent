package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import java.io.IOException;

public interface VacancySourceAdapter {
    GreenhouseFetchResultDTO fetch(GreenhouseBoardDTO board) throws IOException;
}
