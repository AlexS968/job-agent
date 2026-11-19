package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.repository.GmailCutoverRepository;
import com.alexs.jobagent.source.exception.InvalidGmailPageTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "jobagent.gmail.cutover", havingValue = "true")
public class GmailCutoverService {
    private final GmailCutoverRepository state;
    private final LinkedInIntakeService intake;
    private final GmailCutoverClient gmail;
    private final long overlapSeconds;

    public GmailCutoverService(GmailCutoverRepository state, LinkedInIntakeService intake, GmailCutoverClient gmail,
                              @Value("${jobagent.gmail.overlap.seconds:7200}") long overlapSeconds) {
        if (overlapSeconds < 0 || overlapSeconds > 86_400) {
            throw new IllegalArgumentException("Gmail overlap must be between 0 and 86400 seconds");
        }
        this.state = state;
        this.intake = intake;
        this.gmail = gmail;
        this.overlapSeconds = overlapSeconds;
    }

    public record Result(boolean active, boolean morePages, boolean windowRestartRequired, int seen, int review,
                         int applied, long outstanding) {}

    public Result run() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gmail operations require committed database state");
        }
        var window = state.window(overlapSeconds);
        if (window == null) return new Result(false, false, false, 0, 0, 0, state.outstanding());
        int applied = drain(10);
        int seen = 0;
        int review = 0;
        boolean more = false;
        if (window.end() != null) {
            GmailCutoverClient.Page page;
            try {
                page = gmail.livePage(window.start(), window.end(), window.token());
            } catch (InvalidGmailPageTokenException invalid) {
                if (window.token() == null) throw invalid;
                state.restart(window);
                return new Result(true, true, true, 0, 0, applied, state.outstanding());
            }
            if (window.token() != null && window.token().equals(page.nextToken())) {
                throw new IllegalStateException("Gmail returned the same continuation token");
            }
            var result = intake.process(page.ids(), window.start(), window.end());
            state.advance(window, page.nextToken());
            seen = result.messages();
            review = result.reviewRequired();
            more = page.nextToken() != null;
        }
        applied += drain(10 - applied);
        return new Result(true, more, false, seen, review, applied, state.outstanding());
    }

    private int drain(int limit) {
        String label = null;
        int applied = 0;
        while (applied < limit) {
            var claim = state.claim();
            if (claim == null) break;
            if (label == null) label = gmail.processedLabel();
            gmail.applyLabels(claim.id(), label);
            state.applied(claim);
            applied++;
        }
        return applied;
    }
}
