package com.alexs.jobagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class StartupConnectionRetryTest {
    @Test
    void transientStartupFailuresRetryTwiceThenCloseTheSuccessfulConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenThrow(wrappedTimeout()).thenThrow(wrappedTimeout())
                .thenReturn(connection);
        List<Duration> delays = new ArrayList<>();

        new StartupConnectionRetry(dataSource, delays::add).awaitConnection();

        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(20)), delays);
        verify(dataSource, times(3)).getConnection();
        verify(connection).close();
    }

    @Test
    void repeatedTransientFailuresStopAfterThreeAttempts() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(wrappedTimeout());
        List<Duration> delays = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupConnectionRetry(dataSource, delays::add).awaitConnection());

        assertEquals("Database startup connection unavailable after three attempts", failure.getMessage());
        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(20)), delays);
        verify(dataSource, times(3)).getConnection();
    }

    @Test
    void connectionRefusalWithConnectionSqlStateCanRecover() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLException("synthetic", "08006", new ConnectException())).thenReturn(connection);
        List<Duration> delays = new ArrayList<>();

        new StartupConnectionRetry(dataSource, delays::add).awaitConnection();

        assertEquals(List.of(Duration.ofSeconds(10)), delays);
        verify(dataSource, times(2)).getConnection();
        verify(connection).close();
    }

    @Test
    void closingAnEstablishedConnectionIsNotRetried() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        doThrow(new SQLException("synthetic close failure", "08006", new ConnectException()))
                .when(connection).close();
        List<Duration> delays = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new StartupConnectionRetry(dataSource, delays::add).awaitConnection());

        assertEquals("Database startup connection could not be closed", failure.getMessage());
        assertTrue(delays.isEmpty());
        verify(dataSource).getConnection();
    }

    @Test
    void authenticationTlsAndUnclassifiedFailuresNeverRetry() throws Exception {
        var handshake = new SSLHandshakeException("synthetic certificate rejection");
        handshake.initCause(new SocketTimeoutException());
        List<SQLException> failures = List.of(
                new SQLException("synthetic", "28P01", new ConnectException()),
                new SQLException("synthetic", "08001", handshake),
                new SQLException("synthetic", "08001"));
        List<String> categories = List.of("authentication", "TLS verification", "non-transient connection");
        for (int index = 0; index < failures.size(); index++) {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenThrow(failures.get(index));
            List<Duration> delays = new ArrayList<>();

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new StartupConnectionRetry(dataSource, delays::add).awaitConnection());

            assertTrue(failure.getMessage().endsWith(categories.get(index)));
            assertTrue(delays.isEmpty());
            verify(dataSource).getConnection();
        }
    }

    @Test
    void interruptionStopsRetriesAndPreservesInterruptFlag() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(wrappedTimeout());
        try {
            assertThrows(IllegalStateException.class,
                    () -> new StartupConnectionRetry(dataSource, delay -> {
                        throw new InterruptedException();
                    }).awaitConnection());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(dataSource).getConnection();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void flywayValidationRunsOnceAfterConnectionAndIsNeverRetried() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Flyway flyway = mock(Flyway.class);
        when(dataSource.getConnection()).thenReturn(connection);
        IllegalStateException validation = new IllegalStateException("synthetic migration mismatch");
        doThrow(validation).when(flyway).validate();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new CloudFlywayConfig().validateOnlyMigrations(dataSource).migrate(flyway));

        assertSame(validation, failure);
        verify(dataSource).getConnection();
        verify(connection).close();
        verify(flyway).validate();
    }

    private static SQLTransientConnectionException wrappedTimeout() {
        return new SQLTransientConnectionException("synthetic pool timeout", null,
                new SQLException("synthetic connection failure", "08001", new SocketTimeoutException()));
    }
}
