package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.LeverFetchResultDTO;
import com.alexs.jobagent.source.dto.LeverSiteDTO;
import java.io.IOException;

public interface LeverPostingSource {
    LeverFetchResultDTO fetch(LeverSiteDTO site) throws IOException;
}
