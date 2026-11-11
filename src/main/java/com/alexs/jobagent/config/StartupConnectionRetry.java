package com.alexs.jobagent.config;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartupConnectionRetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupConnectionRetry.class);
    private static final int MAX_ATTEMPTS = 3;
    private final DataSource dataSource;
    private final Wait wait;

    StartupConnectionRetry(DataSource dataSource) {
        this(dataSource, Thread::sleep);
    }

    StartupConnectionRetry(DataSource dataSource, Wait wait) {
        this.dataSource = dataSource;
        this.wait = wait;
    }

    void awaitConnection() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Connection established;
            try {
                established = dataSource.getConnection();
            } catch (SQLException failure) {
                FailureKind kind = classify(failure);
                if (kind != FailureKind.TRANSIENT_CONNECTION) {
                    throw new IllegalStateException("Database startup connection failed without retry: "
                            + kind.label());
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Database startup connection unavailable after three attempts");
                }
                Duration delay = Duration.ofSeconds(attempt * 10L);
                LOGGER.warn("Transient database startup connection failure; retrying after {} seconds (attempt {}/{})",
                        delay.toSeconds(), attempt, MAX_ATTEMPTS);
                try {
                    wait.pause(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Database startup connection retry interrupted");
                }
                continue;
            }
            try (established) {
                return;
            } catch (SQLException closeFailure) {
                throw new IllegalStateException("Database startup connection could not be closed");
            }
        }
    }

    static FailureKind classify(SQLException failure) {
        boolean connectionState = false;
        boolean transientTransport = false;
        boolean authentication = false;
        boolean tls = false;
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                authentication |= state != null && state.startsWith("28");
                connectionState |= "08001".equals(state) || "08006".equals(state);
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            tls |= current instanceof SSLException || current instanceof CertificateException;
            transientTransport |= current instanceof SocketTimeoutException
                    || current instanceof ConnectException || current instanceof NoRouteToHostException;
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        if (authentication) {
            return FailureKind.AUTHENTICATION;
        }
        if (tls) {
            return FailureKind.TLS;
        }
        return connectionState && transientTransport ? FailureKind.TRANSIENT_CONNECTION : FailureKind.OTHER;
    }

    enum FailureKind {
        TRANSIENT_CONNECTION("transient connection"),
        AUTHENTICATION("authentication"),
        TLS("TLS verification"),
        OTHER("non-transient connection");

        private final String label;

        FailureKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @FunctionalInterface
    interface Wait {
        void pause(Duration delay) throws InterruptedException;
    }
}
