package com.alexs.jobagent.batch.service;

import com.alexs.jobagent.batch.dto.BatchPipelineResultDTO;
import com.alexs.jobagent.batch.dto.PreparedResultDTO;
import com.alexs.jobagent.batch.repository.ScheduledBatchRepository;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.service.CountryEvidencePolicy;
import com.alexs.jobagent.evaluation.service.RuleEvaluationPolicy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns the atomic preparation of one combined publication and its novelty identities. */
@Service
@ConditionalOnProperty(name = "jobagent.report.enabled", havingValue = "true")
public class ScheduledResultService {
    private final ScheduledBatchRepository repository;
    private final BatchReportFormatter formatter;
    private final TransactionTemplate transaction;

    public ScheduledResultService(ScheduledBatchRepository repository, BatchReportFormatter formatter,
                                  PlatformTransactionManager manager) {
        this.repository = repository;
        this.formatter = formatter;
        this.transaction = new TransactionTemplate(manager);
    }

    public void requireNoUnresolvedPublication() {
        if (repository.hasUnresolvedResult() || repository.hasUnresolvedLegacyReport()) {
            throw new IllegalStateException("Prior report publication requires manual review");
        }
    }

    public PreparedResultDTO prepare(LocalDate date, LoadedProfileDTO de, LoadedProfileDTO at,
                                     List<VacancyEvaluationDTO> deCandidates,
                                     List<VacancyEvaluationDTO> atCandidates,
                                     String pageUrl, BatchPipelineResultDTO run) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Result preparation owns its transaction");
        }
        formatter.requirePageUrl(pageUrl);
        return transaction.execute(status -> prepareInTransaction(date, de, at,
                deCandidates, atCandidates, pageUrl, run));
    }

    private PreparedResultDTO prepareInTransaction(LocalDate date, LoadedProfileDTO de, LoadedProfileDTO at,
                                                  List<VacancyEvaluationDTO> deCandidates,
                                                  List<VacancyEvaluationDTO> atCandidates,
                                                  String pageUrl, BatchPipelineResultDTO run) {
        boolean firstCombined = !repository.hasPublishedResult();
        boolean deBaseline = firstCombined && repository.hasPublishedLegacyReport(de.profile().profileId());
        boolean atBaseline = firstCombined && repository.hasPublishedLegacyReport(at.profile().profileId());
        Set<Long> distinctNew = new HashSet<>();
        Set<Long> distinctRelevant = new HashSet<>();
        List<PendingItem> pending = new ArrayList<>();
        CandidateCounts deCounts = recordCandidates(de, deCandidates, deBaseline,
                distinctNew, distinctRelevant, pending);
        CandidateCounts atCounts = recordCandidates(at, atCandidates, atBaseline,
                distinctNew, distinctRelevant, pending);
        PreparedResultDTO prepared = formatter.combined(date, distinctNew.size(),
                deCounts.newCount(), atCounts.newCount(), distinctRelevant.size(),
                deCounts.relevantCount(), atCounts.relevantCount(),
                deBaseline, atBaseline, pageUrl, run);
        repository.prepareResult(date, de.profile().version(), de.criteriaHash(),
                at.profile().version(), at.criteriaHash(), prepared.subject(), prepared.body());
        // Candidate rows reference the report; insert them only after the report row.
        for (PendingItem item : pending) {
            repository.recordResultItem(date, item.profileId(), item.candidate().vacancyId(),
                    item.candidate().vacancyRevision(), item.profileVersion(), item.criteriaHash(),
                    RuleEvaluationPolicy.VERSION, CountryEvidencePolicy.VERSION, item.disposition());
        }
        return prepared;
    }

    private CandidateCounts recordCandidates(LoadedProfileDTO profile,
                                             List<VacancyEvaluationDTO> candidates, boolean baseline,
                                             Set<Long> distinctNew, Set<Long> distinctRelevant,
                                             List<PendingItem> pending) {
        Set<Long> withinProfile = new HashSet<>();
        int newCount = 0;
        int relevantCount = 0;
        for (VacancyEvaluationDTO candidate : candidates) {
            if (!withinProfile.add(candidate.vacancyId())) {
                throw new IllegalStateException("Duplicate report candidate identity");
            }
            boolean seen = repository.wasPublished(profile.profile().profileId(), candidate.vacancyId());
            if (seen) {
                continue;
            }
            String disposition = baseline ? "BASELINE"
                    : repository.firstObservedAfterLastResult(candidate.vacancyId()) ? "NEW" : "RELEVANT";
            pending.add(new PendingItem(profile.profile().profileId(), profile.profile().version(),
                    profile.criteriaHash(), candidate, disposition));
            if ("NEW".equals(disposition)) {
                newCount++;
                distinctNew.add(candidate.vacancyId());
            } else if ("RELEVANT".equals(disposition)) {
                relevantCount++;
                distinctRelevant.add(candidate.vacancyId());
            }
        }
        return new CandidateCounts(newCount, relevantCount);
    }

    private record CandidateCounts(int newCount, int relevantCount) { }

    private record PendingItem(String profileId, String profileVersion, String criteriaHash,
                               VacancyEvaluationDTO candidate, String disposition) { }

    public void markPublishing(LocalDate date) {
        repository.transitionResult(date, "PREPARED", "PUBLISHING", null);
    }

    public void markPublished(LocalDate date, String messageId) {
        repository.transitionResult(date, "PUBLISHING", "PUBLISHED", messageId);
    }

    public void markUncertain(LocalDate date) {
        repository.transitionResult(date, "PUBLISHING", "UNCERTAIN", null);
    }
}
