package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.SearchQueryDTO;
import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;

public interface SearchGateway {
    SearchResponseDTO search(SearchQueryDTO query) throws SearchFailureException;
}
