package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.dto.SearchPilotResultDTO;
import com.alexs.jobagent.source.dto.SearchQueryDTO;
import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.dto.SearchResultDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;
import com.alexs.jobagent.source.exception.MonthlySearchLimitException;
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
    private static final String AUSTRIA = "austria";
    private static final List<SearchQueryDTO> QUERIES = List.of(
            new SearchQueryDTO("Java Spring backend product company careers Germany", GERMANY, "en", 10),
            new SearchQueryDTO("international SaaS product company Java backend careers", null, "en", 10),
            new SearchQueryDTO("Java backend remote Germany eligible employer careers", GERMANY, "en", 10),
            new SearchQueryDTO("official careers Java Spring Greenhouse Lever Germany", GERMANY, "en", 10));
    private static final List<SearchQueryDTO> RECURRING_QUERIES = List.of(
            new SearchQueryDTO("Java Spring backend product company careers", GERMANY, "en", 10),
            new SearchQueryDTO("Backend Engineer Java Spring official employer careers", GERMANY, "en", 10),
            new SearchQueryDTO("Software Engineer Java backend official careers", AUSTRIA, "en", 10),
            new SearchQueryDTO("Backend Engineer Software Engineer employer careers Greenhouse Lever", AUSTRIA, "en", 10));

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
        return runQueries(QUERIES, QUERY_VERSION, null, INVOCATION_LIMIT, true);
    }

    /** Recurring discovery uses a separate durable UTC-calendar-month allowance. */
    public SearchPilotResultDTO runMonthly() {
        return runQueries(RECURRING_QUERIES, "source-coverage-v1", currentPeriod(), INVOCATION_LIMIT, true);
    }

    /** One Basic lookup for independent employer pages; the caller supplies the shared invocation remainder. */
    public SearchPilotResultDTO searchMonthlyBoardEvidence(AtsSourceIdentityDTO board, int remainingAttempts) {
        if (board == null || board.platform() == null || board.region() == null
                || board.sourceId() == null || remainingAttempts < 1
                || remainingAttempts > INVOCATION_LIMIT
                || !board.sourceId().matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid board evidence search bounds");
        }
        String host = switch (board.platform() + ":" + board.region()) {
            case "GREENHOUSE:global" -> "job-boards.greenhouse.io";
            case "LEVER:global" -> "jobs.lever.co";
            case "LEVER:eu" -> "jobs.eu.lever.co";
            default -> throw new IllegalArgumentException("Unsupported board identity");
        };
        var query = new SearchQueryDTO(host + "/" + board.sourceId() + " official employer careers",
                null, "en", 3);
        return runQueries(List.of(query), "source-coverage-evidence-v1", currentPeriod(),
                remainingAttempts, false);
    }

    /** One Basic request, with no inline retries. The employer lead owns deferred retry. */
    SearchPilotResultDTO searchMonthlyEmployer(String name) {
        var query = new SearchQueryDTO(name + " official company careers", null, "en", 3);
        return runQueries(List.of(query), "source-coverage-employer-v1", currentPeriod(), 1, false);
    }

    private java.time.LocalDate currentPeriod() {
        return java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)).withDayOfMonth(1);
    }

    private SearchPilotResultDTO runQueries(List<SearchQueryDTO> queries, String version,
                                            java.time.LocalDate period, int attemptLimit,
                                            boolean recordCandidates) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Search pilot requires no ambient transaction");
        }
        Invocation state = new Invocation();
        for (int i = 0; i < queries.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                state.cancel();
                return result(state, period);
            }
            state.queries++;
            try {
                if (runQuery(queries.get(i), i + 1, version, period, attemptLimit,
                        recordCandidates, state) == QueryOutcome.STOP) {
                    return result(state, period);
                }
            } catch (MonthlySearchLimitException exhausted) {
                state.monthlyLimitReached = true;
                return result(state, period);
            }
        }
        return result(state, period);
    }

    private QueryOutcome runQuery(SearchQueryDTO query, int queryNumber, String version,
                                  java.time.LocalDate period, int attemptLimit,
                                  boolean recordCandidates, Invocation state) {
        int retry = 1;
        while (retry <= 3 && state.attempts < attemptLimit) {
            if (Thread.currentThread().isInterrupted()) {
                state.cancel();
                return QueryOutcome.STOP;
            }
            SearchFailureException failure = searchOnce(query, queryNumber, version, period,
                    recordCandidates, state);
            if (failure == null) {
                return QueryOutcome.NEXT;
            }
            QueryOutcome decision = handleFailure(failure, state.attempts == attemptLimit ? 3 : retry, state);
            if (decision != QueryOutcome.RETRY) {
                return decision;
            }
            retry++;
        }
        return QueryOutcome.NEXT;
    }

    private SearchFailureException searchOnce(SearchQueryDTO query, int queryNumber, String version,
                                              java.time.LocalDate period, boolean recordCandidates,
                                              Invocation state) {
        Long id = period == null ? reservation.reserve(version, queryNumber)
                : reservation.reserveMonthly(version, queryNumber, currentPeriod());
        if (id == null) {
            if (period != null) {
                throw new MonthlySearchLimitException();
            }
            throw new IllegalStateException("Search pilot budget exhausted; no request sent");
        }
        state.attempts++;
        try {
            SearchResponseDTO response = gateway.search(query);
            repository.finish(id, "SUCCESS", response.requestId(), response.billedCredits());
            response.results().stream().map(SearchResultDTO::url).map(SearchCandidateUrl::normalize)
                    .filter(Objects::nonNull).forEach(url -> {
                        if (recordCandidates) {
                            var kind = DiscoveryLeadPolicy.classify(url);
                            repository.recordCandidate(id, url, kind.name(), kind.priority());
                        }
                        state.urls.add(url);
                    });
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

    private SearchPilotResultDTO result(Invocation state, java.time.LocalDate period) {
        boolean interrupted = Thread.interrupted();
        try {
            return new SearchPilotResultDTO(state.queries, state.attempts,
                    period == null ? repository.remaining() : repository.monthlyRemaining(currentPeriod()),
                    state.failures, state.cancelled, List.copyOf(state.urls), state.monthlyLimitReached);
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
        private boolean monthlyLimitReached;

        private void cancel() {
            Thread.currentThread().interrupt();
            cancelled = true;
            failures++;
        }
    }
}
