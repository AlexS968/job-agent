package com.alexs.jobagent.vacancy.service;

import com.alexs.jobagent.vacancy.dto.CandidateBatchDTO;
import com.alexs.jobagent.vacancy.dto.CandidateAssessmentDTO;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import com.alexs.jobagent.vacancy.repository.CandidateAssessmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateAssessmentService {
    public static final int MAX_PER_INVOCATION = 100;
    private final CandidateAssessmentRepository repository;
    private final CandidateAssessmentPolicy policy;
    private final TransactionTemplate transaction;

    public CandidateAssessmentService(CandidateAssessmentRepository repository, CandidateAssessmentPolicy policy,
                                      PlatformTransactionManager manager) {
        this.repository = repository;
        this.policy = policy;
        this.transaction = new TransactionTemplate(manager);
    }

    public CandidateBatchDTO assessPending() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Candidate assessment requires no ambient transaction");
        }
        int observed = 0;
        int candidates = 0;
        int review = 0;
        int notCandidates = 0;
        for (long id : repository.pending(CandidateAssessmentPolicy.VERSION, MAX_PER_INVOCATION)) {
            String decision = transaction.execute(status -> assessOne(id));
            if (decision == null) {
                continue;
            }
            observed++;
            switch (decision) {
                case "CANDIDATE" -> candidates++;
                case "REVIEW" -> review++;
                case "NOT_CANDIDATE" -> notCandidates++;
                default -> throw new IllegalStateException("Unsupported candidate decision");
            }
        }
        return new CandidateBatchDTO(observed, candidates, review, notCandidates);
    }

    /** Joins the evaluation transaction so the first-stage evidence and evaluation commit together. */
    public CandidateAssessmentDTO forEvaluation(long sourceItemId, VacancyReadDTO vacancy) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || vacancy == null) {
            throw new IllegalStateException("Candidate assessment requires the evaluation transaction");
        }
        CandidateAssessmentDTO existing = repository.find(sourceItemId, CandidateAssessmentPolicy.VERSION);
        if (existing != null) {
            return existing;
        }
        CandidateAssessmentDTO assessed = policy.assess(vacancy.title(), vacancy.description());
        repository.record(sourceItemId, vacancy.id(), CandidateAssessmentPolicy.VERSION, assessed);
        return repository.find(sourceItemId, CandidateAssessmentPolicy.VERSION);
    }

    private String assessOne(long id) {
        var input = repository.lockCurrent(id);
        if (input == null) {
            return null;
        }
        var assessment = policy.assess(input.title(), input.description());
        return repository.record(id, input.vacancyId(), CandidateAssessmentPolicy.VERSION, assessment)
                ? assessment.decision() : null;
    }
}
