package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.dto.LinkedInAlertDTO;
import com.alexs.jobagent.source.exception.LinkedInAlertException;
import com.alexs.jobagent.source.repository.GmailAlertRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LinkedInRepairService {
    private static final int MAX_MESSAGES = 5;
    private static final int MAX_CARDS = 100;
    private final GmailReadClient gmail;
    private final LinkedInAlertParser parser;
    private final GmailAlertRepository messages;
    private final TransactionTemplate transaction;

    public LinkedInRepairService(GmailReadClient gmail, LinkedInAlertParser parser, GmailAlertRepository messages,
                                PlatformTransactionManager manager) {
        this.gmail = gmail;
        this.parser = parser;
        this.messages = messages;
        this.transaction = new TransactionTemplate(manager);
    }

    public record Result(int selected, int completed, int reviewRequired, int skipped, int repairedCards) {}

    public Result repair(List<String> ids) {
        validate(ids);
        int skipped = 0;
        int completed = 0;
        int review = 0;
        int cards = 0;
        for (String id : ids) {
            checkInterrupted();
            if (!messages.repairEligible(id)) {
                skipped++;
                continue;
            }
            LinkedInAlertDTO alert;
            try {
                alert = parser.parse(gmail.alert(id));
            } catch (LinkedInAlertException unsupported) {
                checkInterrupted();
                review++;
                continue;
            }
            if (cards + alert.cards().size() > MAX_CARDS) {
                throw new IllegalStateException("LinkedIn repair card limit reached; earlier repairs remain committed");
            }
            checkInterrupted();
            RepairOutcome outcome = transaction.execute(status -> apply(id, alert));
            switch (outcome) {
                case COMPLETE -> completed++;
                case REVIEW_UPDATED, EVIDENCE_MISMATCH -> review++;
                case SKIPPED -> skipped++;
            }
            if (outcome == RepairOutcome.COMPLETE || outcome == RepairOutcome.REVIEW_UPDATED) {
                cards += alert.cards().size();
            }
        }
        return new Result(ids.size(), completed, review, skipped, cards);
    }

    private RepairOutcome apply(String id, LinkedInAlertDTO alert) {
        var originalTime = messages.lockRepairCandidate(id);
        if (originalTime == null) {
            return RepairOutcome.SKIPPED;
        }
        Set<String> parsedIds = alert.cards().stream().map(LinkedInAlertDTO.CardDTO::jobId).collect(Collectors.toSet());
        if (!id.equals(alert.messageId()) || !originalTime.equals(alert.observedAt()) || parsedIds.isEmpty()
                || !parsedIds.equals(new HashSet<>(messages.cardIds(id)))) {
            return RepairOutcome.EVIDENCE_MISMATCH;
        }
        for (var card : alert.cards()) {
            checkInterrupted();
            messages.storeCardFields(id, card);
        }
        checkInterrupted();
        messages.recordRepair(id, alert.unsupportedLinks());
        return alert.unsupportedLinks() == 0 ? RepairOutcome.COMPLETE : RepairOutcome.REVIEW_UPDATED;
    }

    private static void validate(List<String> ids) {
        if (ids.isEmpty() || ids.size() > MAX_MESSAGES || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,128}"))) {
            throw new IllegalArgumentException("LinkedIn repair requires one to five distinct message IDs");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("LinkedIn repair owns its transaction boundary");
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("LinkedIn repair interrupted");
        }
    }

    private enum RepairOutcome { COMPLETE, REVIEW_UPDATED, EVIDENCE_MISMATCH, SKIPPED }
}
