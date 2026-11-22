package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.repository.GmailCutoverRepository;
import com.alexs.jobagent.source.exception.GmailAccessException;
import com.alexs.jobagent.source.exception.InvalidGmailPageTokenException;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "jobagent.gmail.cutover", havingValue = "true")
public class GmailCutoverService {
    private static final int MAX_PAGES = 3;
    private static final int MAX_ACTIONS = 10;
    private final GmailCutoverRepository state;
    private final LinkedInIntakeService intake;
    private final GmailCutoverClient gmail;
    private final long overlapSeconds;
    private final Clock clock;

    public GmailCutoverService(GmailCutoverRepository state, LinkedInIntakeService intake, GmailCutoverClient gmail,
                              @Value("${jobagent.gmail.overlap.seconds:7200}") long overlapSeconds, Clock clock) {
        if (overlapSeconds < 0 || overlapSeconds > 86_400) {
            throw new IllegalArgumentException("Gmail overlap must be between 0 and 86400 seconds");
        }
        this.state = state;
        this.intake = intake;
        this.gmail = gmail;
        this.overlapSeconds = overlapSeconds;
        this.clock = clock;
    }

    public record Result(boolean active, boolean morePages, boolean windowRestartRequired, int seen, int review,
                         int applied, long outstanding) {}

    public Result run() {
        return collect(1, () -> true);
    }

    public Result runUntil(Instant deadline) {
        // Allow one Gmail operation, including a possible 20-second token refresh.
        return collect(MAX_PAGES, () -> !clock.instant().plusSeconds(45).isAfter(deadline));
    }

    private Result collect(int maxPages, BooleanSupplier hasTime) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gmail operations require committed database state");
        }
        requireNotInterrupted();
        var window = state.window(overlapSeconds);
        if (window == null) {
            return new Result(false, false, false, 0, 0, 0, state.outstanding());
        }
        int applied = maxPages == 1 ? drain(MAX_ACTIONS, hasTime) : 0;
        int seen = 0;
        int review = 0;
        boolean more = window.end() != null;
        boolean restart = false;
        for (int i = 0; more && i < maxPages && hasTime.getAsBoolean(); i++) {
            requireNotInterrupted();
            GmailCutoverClient.Page page;
            try {
                page = gmail.livePage(window.start(), window.end(), window.token());
            } catch (InvalidGmailPageTokenException invalid) {
                if (window.token() == null) {
                    throw invalid;
                }
                state.restart(window);
                restart = true;
                break;
            }
            if (window.token() != null && window.token().equals(page.nextToken())) {
                throw new GmailAccessException("Gmail returned the same continuation token");
            }
            var result = intake.process(page.ids(), window.start(), window.end(), hasTime);
            seen += result.messages();
            review += result.reviewRequired();
            if (result.messages() < page.ids().size()) {
                break;
            }
            state.advance(window, page.nextToken());
            more = page.nextToken() != null;
            if (more && i + 1 < maxPages && hasTime.getAsBoolean()) {
                window = state.window(overlapSeconds);
                if (window == null) {
                    return new Result(false, true, false, seen, review, applied, state.outstanding());
                }
            }
        }
        if (!restart) {
            applied += drain(MAX_ACTIONS - applied, hasTime);
        }
        requireNotInterrupted();
        return new Result(true, more, restart, seen, review, applied, state.outstanding());
    }

    private int drain(int limit, BooleanSupplier hasTime) {
        String label = null;
        int applied = 0;
        while (applied < limit && hasTime.getAsBoolean()) {
            requireNotInterrupted();
            var claim = state.claim();
            if (claim == null) {
                break;
            }
            if (label == null) {
                label = gmail.processedLabel();
            }
            if (!hasTime.getAsBoolean()) {
                break;
            }
            gmail.applyLabels(claim.id(), label);
            state.applied(claim);
            applied++;
        }
        return applied;
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Gmail cutover interrupted");
        }
    }
}
