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
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SearchPilotService {
    public static final String QUERY_VERSION = "pilot-v1";
    private static final int INVOCATION_LIMIT = 12;
    private static final String GERMANY = "germany";
    private static final List<SearchQueryDTO> QUERIES = List.of(
            new SearchQueryDTO("Java Spring backend product company careers Germany", GERMANY, "en", 10),
            new SearchQueryDTO("international SaaS product company Java backend careers", null, "en", 10),
            new SearchQueryDTO("Java backend remote Germany eligible employer careers", GERMANY, "en", 10),
            new SearchQueryDTO("official careers Java Spring Greenhouse Lever Germany", GERMANY, "en", 10));

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
        Invocation state = new Invocation();
        for (int i = 0; i < QUERIES.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                state.cancel();
                return result(state);
            }
            state.queries++;
            if (runQuery(QUERIES.get(i), i + 1, state) == QueryOutcome.STOP) {
                return result(state);
            }
        }
        return result(state);
    }

    private QueryOutcome runQuery(SearchQueryDTO query, int queryNumber, Invocation state) {
        int retry = 1;
        while (retry <= 3 && state.attempts < INVOCATION_LIMIT) {
            if (Thread.currentThread().isInterrupted()) {
                state.cancel();
                return QueryOutcome.STOP;
            }
            SearchFailureException failure = searchOnce(query, queryNumber, state);
            if (failure == null) {
                return QueryOutcome.NEXT;
            }
            QueryOutcome decision = handleFailure(failure, retry, state);
            if (decision != QueryOutcome.RETRY) {
                return decision;
            }
            retry++;
        }
        return QueryOutcome.NEXT;
    }

    private SearchFailureException searchOnce(SearchQueryDTO query, int queryNumber, Invocation state) {
        Long id = reservation.reserve(QUERY_VERSION, queryNumber);
        if (id == null) {
            throw new IllegalStateException("Search pilot budget exhausted; no request sent");
        }
        state.attempts++;
        try {
            SearchResponseDTO response = gateway.search(query);
            repository.finish(id, "SUCCESS", response.requestId(), response.billedCredits());
            response.results().stream().map(SearchResultDTO::url).map(SearchCandidateUrl::normalize)
                    .filter(Objects::nonNull).forEach(state.urls::add);
            return null;
        } catch (SearchFailureException failure) {
            finishFailure(id, failure);
            return failure;
        }
    }

    private void finishFailure(long id, SearchFailureException failure) {
        boolean interrupted = Thread.interrupted();
        try {
            repository.finish(id, failure.kind().name(), failure.requestId(), null);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private QueryOutcome handleFailure(SearchFailureException failure, int retry, Invocation state) {
        if (failure.kind() == SearchFailureException.Kind.CANCELLED || Thread.currentThread().isInterrupted()) {
            state.cancel();
            return QueryOutcome.STOP;
        }
        if (failure.kind() == SearchFailureException.Kind.QUOTA
                || failure.kind() == SearchFailureException.Kind.AUTHENTICATION) {
            state.failures++;
            return QueryOutcome.STOP;
        }
        if (failure.kind() != SearchFailureException.Kind.TRANSIENT || retry == 3) {
            state.failures++;
            return QueryOutcome.NEXT;
        }
        try {
            Duration delay = SourceRetryPolicy.delay(failure.retryAfter(), retry, Duration.ofSeconds(2), clock);
            wait.await(delay);
            return QueryOutcome.RETRY;
        } catch (SourceRetryPolicy.BudgetExceededException exceeded) {
            state.failures++;
            return QueryOutcome.STOP;
        } catch (InterruptedException interrupted) {
            state.cancel();
            return QueryOutcome.STOP;
        }
    }

    private SearchPilotResultDTO result(Invocation state) {
        boolean interrupted = Thread.interrupted();
        try {
            return new SearchPilotResultDTO(state.queries, state.attempts, repository.remaining(),
                    state.failures, state.cancelled, List.copyOf(state.urls));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum QueryOutcome { RETRY, NEXT, STOP }

    private static final class Invocation {
        private final Set<String> urls = new LinkedHashSet<>();
        private int queries;
        private int attempts;
        private int failures;
        private boolean cancelled;

        private void cancel() {
            Thread.currentThread().interrupt();
            cancelled = true;
            failures++;
        }
    }
}
