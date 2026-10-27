package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.repository.EvaluationRepository;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.source.service.SourceEvidenceService;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import com.alexs.jobagent.vacancy.service.VacancyReadService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EvaluationService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_BATCH = 500;
    private static final int MAX_REPORT = 50;
    private static final Comparator<VacancyEvaluationDTO> ORDER = Comparator
            .comparingInt(VacancyEvaluationDTO::preferenceRank)
            .thenComparingInt(item -> roleOrder(item.roleClassification()))
            .thenComparingLong(VacancyEvaluationDTO::vacancyId);
    private final VacancyReadService vacancies;
    private final SourceEvidenceService sources;
    private final EvaluationRepository evaluations;
    private final RuleEvaluationPolicy policy;
    private final TransactionTemplate transaction;

    public EvaluationService(VacancyReadService vacancies, SourceEvidenceService sources,
                             EvaluationRepository evaluations, RuleEvaluationPolicy policy,
                             PlatformTransactionManager manager) {
        this.vacancies = vacancies;
        this.sources = sources;
        this.evaluations = evaluations;
        this.policy = policy;
        this.transaction = new TransactionTemplate(manager);
    }

    public EvaluationBatchResultDTO evaluate(LoadedProfileDTO profile, long afterId, int maximum,
                                             String policyVersion) {
        rejectAmbient();
        if (afterId < 0 || maximum < 1 || maximum > MAX_BATCH || !validVersion(policyVersion)) {
            throw new IllegalArgumentException("Invalid evaluation batch arguments");
        }
        transaction.execute(status -> {
            evaluations.registerProfile(profile);
            return null;
        });
        int examined = 0;
        int created = 0;
        long cursor = afterId;
        while (examined < maximum) {
            List<Long> ids = vacancies.idsAfter(cursor, Math.min(PAGE_SIZE, maximum - examined));
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                if (Boolean.TRUE.equals(transaction.execute(status -> evaluateOne(id, profile, policyVersion)))) {
                    created++;
                }
                examined++;
                cursor = id;
            }
        }
        return new EvaluationBatchResultDTO(examined, created, examined - created, cursor,
                !vacancies.idsAfter(cursor, 1).isEmpty());
    }

    public ShortlistDTO shortlist(LoadedProfileDTO profile, int limit, String policyVersion) {
        rejectAmbient();
        if (limit < 1 || limit > MAX_REPORT || !validVersion(policyVersion)) {
            throw new IllegalArgumentException("Invalid shortlist arguments");
        }
        evaluations.requireRegisteredProfile(profile);
        List<VacancyEvaluationDTO> suitable = new ArrayList<>();
        List<VacancyEvaluationDTO> review = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<Long> ids = vacancies.idsAfter(cursor, PAGE_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                VacancyEvaluationDTO current = transaction.execute(status -> current(id, profile, policyVersion));
                if (current != null && "SUITABLE".equals(current.decision())) {
                    addBounded(suitable, current, limit);
                } else if (current != null && "NEEDS_REVIEW".equals(current.decision())) {
                    addBounded(review, current, limit);
                }
            }
            cursor = ids.getLast();
        }
        int remaining = Math.max(0, limit - suitable.size());
        return new ShortlistDTO(List.copyOf(suitable), List.copyOf(review.subList(0,
                Math.min(remaining, review.size()))));
    }

    private boolean evaluateOne(long id, LoadedProfileDTO profile, String policyVersion) {
        VacancyReadDTO vacancy = vacancies.lock(id);
        if (vacancy == null) {
            throw new IllegalStateException("Vacancy disappeared during evaluation");
        }
        SourceEvidenceDTO source = sources.latestForVacancy(id);
        if (source == null) {
            throw new IllegalStateException("Vacancy provenance is missing");
        }
        String revision = policy.revision(vacancy, source);
        if (evaluations.current(id, revision, profile, policyVersion) != null) {
            return false;
        }
        return evaluations.insert(policy.evaluate(vacancy, source, profile, revision), profile, policyVersion);
    }

    private VacancyEvaluationDTO current(long id, LoadedProfileDTO profile, String policyVersion) {
        VacancyReadDTO vacancy = vacancies.lock(id);
        if (vacancy == null) {
            return null;
        }
        SourceEvidenceDTO source = sources.latestForVacancy(id);
        if (source == null) {
            return null;
        }
        return evaluations.current(id, policy.revision(vacancy, source), profile, policyVersion);
    }

    private static void addBounded(List<VacancyEvaluationDTO> results, VacancyEvaluationDTO result, int limit) {
        results.add(result);
        results.sort(ORDER);
        if (results.size() > limit) {
            results.removeLast();
        }
    }

    private static int roleOrder(String classification) {
        return switch (classification) {
            case "PREFERRED" -> 0;
            case "RESERVE" -> 1;
            default -> 2;
        };
    }

    private static boolean validVersion(String text) {
        return text != null && text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    }

    private static void rejectAmbient() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Evaluation owns its transaction boundary");
        }
    }
}
