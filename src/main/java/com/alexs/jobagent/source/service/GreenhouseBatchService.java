package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.GreenhouseBatchResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.dto.GreenhouseFetchResultDTO;
import com.alexs.jobagent.source.dto.GreenhouseListingDTO;
import com.alexs.jobagent.source.exception.IngestionConflictException;
import com.alexs.jobagent.source.exception.GreenhouseFetchException;
import com.alexs.jobagent.source.exception.InvalidIngestionException;
import com.alexs.jobagent.vacancy.exception.VacancyConflictException;
import com.alexs.jobagent.vacancy.dto.VacancyChangeDTO;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GreenhouseBatchService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GreenhouseBatchService.class);
    private final VacancySourceAdapter adapter;
    private final GreenhouseIngestionService ingestion;
    private final Clock clock;

    public GreenhouseBatchService(VacancySourceAdapter adapter, GreenhouseIngestionService ingestion, Clock clock) {
        this.adapter = adapter;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    public GreenhouseBatchResultDTO run(String configuredBoards) {
        List<GreenhouseBoardDTO> boards = parseBoards(configuredBoards);
        int requests = 0;
        int fetched = 0;
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int rejected = 0;
        int failed = 0;
        boardsLoop: for (GreenhouseBoardDTO board : boards) {
            GreenhouseFetchResultDTO batch;
            Instant fetchedAt;
            try {
                batch = adapter.fetch(board);
                fetchedAt = clock.instant();
                requests += batch.requests();
                fetched += batch.listings().size();
            } catch (IOException | IllegalArgumentException fetchFailure) {
                if (fetchFailure instanceof GreenhouseFetchException reported) {
                    requests += reported.attempts();
                }
                failed++;
                LOGGER.warn("Greenhouse board fetch failed: board={}, reason={}", board.token(),
                        fetchFailure instanceof IOException ? "FETCH_ERROR" : "CONFIGURATION_ERROR");
                continue;
            }
            for (GreenhouseListingDTO listing : batch.listings()) {
                try {
                    VacancyChangeDTO change = ingestion.ingest(board, listing, fetchedAt).change();
                    switch (change) {
                        case CREATED -> created++;
                        case UPDATED -> updated++;
                        case UNCHANGED -> unchanged++;
                        case STALE -> unchanged++;
                    }
                } catch (RuntimeException failure) {
                    boolean invalid = failure instanceof InvalidIngestionException
                            || failure instanceof IngestionConflictException
                            || failure instanceof VacancyConflictException;
                    if (invalid) {
                        rejected++;
                    } else {
                        failed++;
                    }
                    LOGGER.warn("Greenhouse listing import failed: board={}, post_id={}, reason={}",
                            board.token(), listing.id(), invalid ? "REJECTED_INPUT" : "PROCESSING_ERROR");
                    if (!invalid) {
                        break boardsLoop;
                    }
                }
            }
        }
        GreenhouseBatchResultDTO result = new GreenhouseBatchResultDTO(requests, fetched, created,
                updated, unchanged, rejected, failed);
        LOGGER.info("Greenhouse invocation: requests={}, fetched={}, created={}, updated={}, unchanged={}, rejected={}, failed={}",
                requests, fetched, created, updated, unchanged, rejected, failed);
        return result;
    }

    private static List<GreenhouseBoardDTO> parseBoards(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("GREENHOUSE_BOARDS is required for greenhouse mode");
        }
        String[] entries = configured.split(",", -1);
        if (entries.length > 3) {
            throw new IllegalArgumentException("At most three Greenhouse boards are allowed per invocation");
        }
        List<GreenhouseBoardDTO> boards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entries) {
            String[] fields = entry.split("\\|", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()
                    || !seen.add(fields[0])) {
                throw new IllegalArgumentException("Invalid GREENHOUSE_BOARDS entry");
            }
            boards.add(new GreenhouseBoardDTO(fields[0], fields[1]));
        }
        return List.copyOf(boards);
    }
}
