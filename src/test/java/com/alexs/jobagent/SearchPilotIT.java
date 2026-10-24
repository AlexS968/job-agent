package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.SearchResponseDTO;
import com.alexs.jobagent.source.dto.SearchResultDTO;
import com.alexs.jobagent.source.repository.SearchPilotRepository;
import com.alexs.jobagent.source.exception.SearchFailureException;
import com.alexs.jobagent.source.service.SearchPilotService;
import com.alexs.jobagent.source.service.SearchAttemptReservationService;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
class SearchPilotIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withDatabaseName("job_agent_search_test").withUsername("test_user")
            .withPassword("synthetic_test_secret_123");
    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired SearchPilotRepository repository;
    @Autowired SearchAttemptReservationService reservation;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE search_pilot_attempts RESTART IDENTITY");
        jdbc.update("UPDATE search_pilot_budget SET reserved_attempts = 0 WHERE id = 1");
    }

    @Test
    void retriesReserveSeparatelyAndRestartDoesNotResetBudget() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            if (calls.getAndIncrement() == 0) {
                throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, "r1", "0");
            }
            return new SearchResponseDTO(List.of(new SearchResultDTO("https://example.org/jobs?x=1", "Title", "Hint", 1),
                    new SearchResultDTO("https://example.org/jobs?x=1", "Title", "Hint", 2)), "r2", 1);
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(5, result.attempts());
        assertEquals(95, result.remaining());
        assertEquals(List.of("https://example.org/jobs?x=1"), result.unverifiedUrls());
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts WHERE outcome = 'TRANSIENT'", Integer.class));
        assertEquals(95, new SearchPilotRepository(jdbc).remaining());
    }

    @Test
    void concurrentReservationCannotExceedHundredAndUncertainCallIsNotRefunded() {
        var attempts = IntStream.range(0, 120).mapToObj(index -> CompletableFuture.supplyAsync(
                () -> reservation.reserve("pilot-v1", 1))).toList();
        long reserved = attempts.stream().map(CompletableFuture::join).filter(Objects::nonNull).count();
        assertEquals(100, reserved);
        assertEquals(0, repository.remaining());
        assertEquals(100, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts WHERE outcome = 'RESERVED'", Integer.class));
        assertNull(reservation.reserve("pilot-v1", 1));
    }

    @Test
    void failuresStopWithoutRetryingAuthenticationOrQuota() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            calls.incrementAndGet();
            throw new SearchFailureException(SearchFailureException.Kind.QUOTA, null, null);
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(1, calls.get());
        assertEquals(1, result.failures());
        assertEquals(99, result.remaining());
    }

    @Test
    void transientFailuresRespectTwelveAttemptInvocationLimit() {
        SearchPilotService pilot = new SearchPilotService(query -> {
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, null, "0");
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(12, result.attempts());
        assertEquals(88, result.remaining());
        assertEquals(4, result.failures());
    }

    @Test
    void ambientTransactionCannotRollBackAnAttemptReservationAfterHttp() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            calls.incrementAndGet();
            return new SearchResponseDTO(List.of(), null, null);
        }, repository, reservation, Clock.systemUTC());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, pilot::run);
            status.setRollbackOnly();
        });
        assertEquals(0, calls.get());
        assertEquals(100, repository.remaining());
    }

    @Test
    void longRetryAfterStopsInvocationBeforeAnyFurtherRequest() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            calls.incrementAndGet();
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, "r1", "9");
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(1, calls.get());
        assertEquals(1, result.attempts());
        assertEquals(1, result.failures());
        assertEquals(99, result.remaining());
    }

    @Test
    void interruptedRetryWaitStopsAllQueriesAndKeepsPriorResults() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            if (calls.getAndIncrement() == 0) {
                return new SearchResponseDTO(List.of(new SearchResultDTO(
                        "https://example.org/jobs/1", "Title", "Hint", 1)), "ok", 1);
            }
            throw new SearchFailureException(SearchFailureException.Kind.TRANSIENT, "retry", "0");
        }, repository, reservation, Clock.systemUTC(), delay -> { throw new InterruptedException("cancelled"); });
        try {
            var result = pilot.run();
            assertTrue(result.cancelled());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, calls.get());
            assertEquals(2, result.attempts());
            assertEquals(1, result.failures());
            assertEquals(List.of("https://example.org/jobs/1"), result.unverifiedUrls());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedHttpStopsAllQueriesWithoutRetry() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new SearchFailureException(SearchFailureException.Kind.CANCELLED, null, null);
        }, repository, reservation, Clock.systemUTC());
        try {
            var result = pilot.run();
            assertTrue(result.cancelled());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
            assertEquals(1, result.attempts());
            assertEquals(1, result.failures());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts WHERE outcome = 'CANCELLED'", Integer.class));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedAttemptInsertionRollsBackBudgetIncrement() {
        String invalidVersion = "x".repeat(33);
        assertThrows(DataAccessException.class, () -> reservation.reserve(invalidVersion, 1));
        assertEquals(100, repository.remaining());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts", Integer.class));
    }

    @Test
    void laterPermanentFailureRetainsEarlierCandidateAndContinues() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                throw new SearchFailureException(SearchFailureException.Kind.PERMANENT, "failed", null);
            }
            return new SearchResponseDTO(call == 1
                    ? List.of(new SearchResultDTO("https://example.org/jobs/1", null, null, 1))
                    : List.of(), "ok", null);
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(4, calls.get());
        assertEquals(4, result.attempts());
        assertEquals(1, result.failures());
        assertFalse(result.cancelled());
        assertEquals(List.of("https://example.org/jobs/1"), result.unverifiedUrls());
    }

    @Test
    void authenticationAfterSuccessStopsBeforeFurtherQueries() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            if (calls.incrementAndGet() == 2) {
                throw new SearchFailureException(SearchFailureException.Kind.AUTHENTICATION, null, null);
            }
            return new SearchResponseDTO(List.of(new SearchResultDTO("https://example.org/jobs/1", null, null, 1)), null, null);
        }, repository, reservation, Clock.systemUTC());
        var result = pilot.run();
        assertEquals(2, calls.get());
        assertEquals(2, result.attempts());
        assertEquals(1, result.failures());
        assertEquals(List.of("https://example.org/jobs/1"), result.unverifiedUrls());
    }

    @Test
    void budgetExhaustionAfterTwoCommittedRequestsDoesNotSendThird() {
        for (int i = 0; i < 98; i++) {
            reservation.reserve("prior", 1);
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SearchPilotService pilot = new SearchPilotService(query -> {
            calls.incrementAndGet();
            return new SearchResponseDTO(List.of(), null, null);
        }, repository, reservation, Clock.systemUTC());
        assertThrows(IllegalStateException.class, pilot::run);
        assertEquals(2, calls.get());
        assertEquals(0, repository.remaining());
        assertEquals(100, jdbc.queryForObject("SELECT count(*) FROM search_pilot_attempts", Integer.class));
    }

    @Test
    void preInterruptedInvocationDoesNotReserveOrCallProvider() {
        SearchPilotService pilot = new SearchPilotService(query -> {
            fail("HTTP must not start");
            return new SearchResponseDTO(List.of(), null, null);
        }, repository, reservation, Clock.systemUTC());
        Thread.currentThread().interrupt();
        try {
            var result = pilot.run();
            assertTrue(result.cancelled());
            assertEquals(0, result.attempts());
            assertEquals(0, result.queries());
            assertEquals(1, result.failures());
            assertEquals(100, result.remaining());
        } finally {
            Thread.interrupted();
        }
    }
}
