package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.SearchPilotResultDTO;
import com.alexs.jobagent.source.dto.SearchQueryDTO;
import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.dto.SearchResultDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;
import com.alexs.jobagent.source.repository.SearchPilotRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SearchPilotService {
    public static final String QUERY_VERSION = "pilot-v1";
    private static final int INVOCATION_LIMIT = 12;
    private static final List<SearchQueryDTO> QUERIES = List.of(
            new SearchQueryDTO("Java Spring backend product company careers Germany", "germany", "en", 10),
            new SearchQueryDTO("international SaaS product company Java backend careers", null, "en", 10),
            new SearchQueryDTO("Java backend remote Germany eligible employer careers", "germany", "en", 10),
            new SearchQueryDTO("official careers Java Spring Greenhouse Lever Germany", "germany", "en", 10));

    private final SearchGateway gateway;
    private final SearchPilotRepository repository;
    private final SearchAttemptReservationService reservation;
    private final Clock clock;
    private final RetryWait wait;

    @Autowired
    public SearchPilotService(SearchGateway gateway, SearchPilotRepository repository,
                              SearchAttemptReservationService reservation, Clock clock) {
        this(gateway, repository, reservation, clock, delay -> Thread.sleep(delay.toMillis()));
    }

    public SearchPilotService(SearchGateway gateway, SearchPilotRepository repository,
                              SearchAttemptReservationService reservation, Clock clock, RetryWait wait) {
        this.gateway = gateway;
        this.repository = repository;
        this.reservation = reservation;
        this.clock = clock;
        this.wait = wait;
    }

    public List<SearchQueryDTO> plannedQueries() {
        return QUERIES;
    }

    public SearchPilotResultDTO run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Search pilot requires no ambient transaction");
        }
        Set<String> urls = new LinkedHashSet<>();
        int attempts = 0;
        int failures = 0;
        int queries = 0;
        for (int i = 0; i < QUERIES.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                return result(queries, attempts, failures + 1, true, urls);
            }
            SearchQueryDTO query = QUERIES.get(i);
            queries++;
            for (int retry = 1; retry <= 3 && attempts < INVOCATION_LIMIT; retry++) {
                if (Thread.currentThread().isInterrupted()) {
                    return result(queries, attempts, failures + 1, true, urls);
                }
                Long id = reservation.reserve(QUERY_VERSION, i + 1);
                if (id == null) {
                    throw new IllegalStateException("Search pilot budget exhausted; no request sent");
                }
                attempts++;
                try {
                    SearchResponseDTO response = gateway.search(query);
                    repository.finish(id, "SUCCESS", response.requestId(), response.billedCredits());
                    response.results().stream().map(SearchResultDTO::url).map(SearchCandidateUrl::normalize)
                            .filter(url -> url != null).forEach(urls::add);
                    break;
                } catch (SearchFailureException failure) {
                    boolean wasInterrupted = Thread.interrupted();
                    try {
                        repository.finish(id, failure.kind().name(), failure.requestId(), null);
                    } finally {
                        if (wasInterrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (failure.kind() == SearchFailureException.Kind.CANCELLED || wasInterrupted) {
                        Thread.currentThread().interrupt();
                        return result(queries, attempts, failures + 1, true, urls);
                    }
                    if (failure.kind() == SearchFailureException.Kind.QUOTA
                            || failure.kind() == SearchFailureException.Kind.AUTHENTICATION) {
                        return result(queries, attempts, failures + 1, false, urls);
                    }
                    if (failure.kind() != SearchFailureException.Kind.TRANSIENT || retry == 3) {
                        failures++;
                        break;
                    }
                    Duration delay;
                    try {
                        delay = SourceRetryPolicy.delay(failure.retryAfter(), retry,
                                Duration.ofSeconds(2), clock);
                    } catch (SourceRetryPolicy.BudgetExceededException tooLong) {
                        return result(queries, attempts, failures + 1, false, urls);
                    }
                    try {
                        wait.await(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return result(queries, attempts, failures + 1, true, urls);
                    }
                }
            }
        }
        return result(queries, attempts, failures, false, urls);
    }

    private SearchPilotResultDTO result(int queries, int attempts, int failures, boolean cancelled, Set<String> urls) {
        boolean interrupted = Thread.interrupted();
        try {
            return new SearchPilotResultDTO(queries, attempts, repository.remaining(), failures, cancelled, List.copyOf(urls));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
