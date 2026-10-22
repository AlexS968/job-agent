package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.LeverBatchResultDTO;
import com.alexs.jobagent.source.dto.LeverFetchResultDTO;
import com.alexs.jobagent.source.dto.LeverListingDTO;
import com.alexs.jobagent.source.dto.LeverSiteDTO;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.source.exception.LeverFetchException;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LeverBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LeverBatchService.class);
    private final LeverPostingSource source;
    private final LeverIngestionService ingestion;

    public LeverBatchService(LeverPostingSource source, LeverIngestionService ingestion) {
        this.source = source;
        this.ingestion = ingestion;
    }

    public LeverBatchResultDTO run(String configuredSites) {
        List<LeverSiteDTO> sites = parseSites(configuredSites);
        int requests = 0;
        int fetched = 0;
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int rejected = 0;
        int failed = 0;
        sitesLoop: for (LeverSiteDTO site : sites) {
            LeverFetchResultDTO batch;
            try {
                batch = source.fetch(site);
                requests += batch.requests();
                fetched += batch.listings().size();
            } catch (IOException | IllegalArgumentException fetchFailure) {
                if (fetchFailure instanceof LeverFetchException reported) {
                    requests += reported.requests();
                }
                failed++;
                LOGGER.warn("Lever site fetch failed: region={}, site={}, reason={}",
                        site.region(), site.site(),
                        fetchFailure instanceof IOException ? "FETCH_ERROR" : "CONFIGURATION_ERROR");
                continue;
            }
            for (LeverListingDTO listing : batch.listings()) {
                try {
                    VacancyChangeDTO change = ingestion.ingest(site, listing, batch.observationStartedAt()).change();
                    switch (change) {
                        case CREATED -> created++;
                        case UPDATED -> updated++;
                        case UNCHANGED, STALE -> unchanged++;
                    }
                } catch (RuntimeException failure) {
                    boolean invalid = failure instanceof InvalidIngestionException
                            || failure instanceof VacancyConflictException;
                    if (invalid) {
                        rejected++;
                    } else {
                        failed++;
                    }
                    LOGGER.warn("Lever listing import failed: region={}, site={}, post_id={}, reason={}",
                            site.region(), site.site(), listing.id(), invalid ? "REJECTED_INPUT" : "PROCESSING_ERROR");
                    if (!invalid) {
                        break sitesLoop;
                    }
                }
            }
        }
        LeverBatchResultDTO result = new LeverBatchResultDTO(requests, fetched, created, updated,
                unchanged, rejected, failed);
        LOGGER.info("Lever invocation: requests={}, fetched={}, created={}, updated={}, unchanged={}, rejected={}, failed={}",
                requests, fetched, created, updated, unchanged, rejected, failed);
        return result;
    }

    private static List<LeverSiteDTO> parseSites(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("LEVER_SITES is required for lever mode");
        }
        String[] entries = configured.split(",", -1);
        if (entries.length > 2) {
            throw new IllegalArgumentException("At most two Lever sites are allowed per invocation");
        }
        List<LeverSiteDTO> sites = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entries) {
            String[] fields = entry.split("\\|", -1);
            if (fields.length != 3 || !seen.add(fields[0] + ":" + fields[1])) {
                throw new IllegalArgumentException("Invalid LEVER_SITES entry");
            }
            LeverSiteDTO site = new LeverSiteDTO(fields[0], fields[1], fields[2]);
            LeverSourceAdapter.validateSite(site);
            sites.add(site);
        }
        return List.copyOf(sites);
    }
}
