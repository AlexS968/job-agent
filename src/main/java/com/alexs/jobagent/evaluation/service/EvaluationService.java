package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.dto.SelectedExtractionDTO;
import com.alexs.jobagent.evaluation.dto.EvaluationStatusDTO;
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
    private final ExtractionAwareEvaluationPolicy extractionPolicy;
    private final ExtractionEvidenceService extractionEvidence;
    private final TransactionTemplate transaction;

    public EvaluationService(VacancyReadService vacancies, SourceEvidenceService sources,
                             EvaluationRepository evaluations, RuleEvaluationPolicy policy,
                             ExtractionAwareEvaluationPolicy extractionPolicy,
                             ExtractionEvidenceService extractionEvidence,
                             PlatformTransactionManager manager) {
        this.vacancies = vacancies;
        this.sources = sources;
        this.evaluations = evaluations;
        this.policy = policy;
        this.extractionPolicy = extractionPolicy;
        this.extractionEvidence = extractionEvidence;
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

    /** Joins the caller's receipt transaction; the vacancy row lock fences concurrent updates. */
    public EventEvaluationDTO evaluateEventInTransaction(long vacancyId, long sourceItemId, String revision,
                                                          LoadedProfileDTO profile) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Event evaluation requires the receipt transaction");
        }
        VacancyReadDTO vacancy = vacancies.lock(vacancyId);
        SourceEvidenceDTO source = vacancy == null ? null : sources.latestForVacancy(vacancyId);
        if (source == null) {
            throw new IllegalStateException("Event vacancy provenance is unavailable");
        }
        if (source.sourceItemId() != sourceItemId
                || !policy.revision(vacancy, source).equals(revision)) {
            return new EventEvaluationDTO(true, null);
        }
        evaluations.registerProfile(profile);
        evaluateLocked(vacancy, source, profile, RuleEvaluationPolicy.VERSION, revision);
        return new EventEvaluationDTO(false,
                evaluations.idFor(vacancyId, revision, profile, RuleEvaluationPolicy.VERSION));
    }

    public record EventEvaluationDTO(boolean stale, Long evaluationId) { }

    public EvaluationStatusDTO evaluationStatus(long id) {
        return evaluations.status(id);
    }

    public ShortlistDTO shortlist(LoadedProfileDTO profile, int limit, String policyVersion) {
        return shortlist(profile, limit, policyVersion, false);
    }

    /** Report projection: trusted live source identities and established roles only. */
    public ShortlistDTO reportShortlist(LoadedProfileDTO profile, int limit, String policyVersion) {
        return shortlist(profile, limit, policyVersion, true);
    }

    private ShortlistDTO shortlist(LoadedProfileDTO profile, int limit, String policyVersion, boolean reportOnly) {
        rejectAmbient();
        if (limit < 1 || limit > MAX_REPORT || !validVersion(policyVersion)) {
            throw new IllegalArgumentException("Invalid shortlist arguments");
        }
        evaluations.requireRegisteredProfile(profile);
        List<VacancyEvaluationDTO> suitable = new ArrayList<>();
        List<VacancyEvaluationDTO> review = new ArrayList<>();
        int totalSuitable = 0;
        int totalReview = 0;
        long cursor = 0;
        while (true) {
            List<Long> ids = vacancies.idsAfter(cursor, PAGE_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                VacancyEvaluationDTO current = transaction.execute(status -> current(id, profile, policyVersion,
                        reportOnly));
                if (current != null && (!reportOnly || !"UNMATCHED".equals(current.roleClassification()))) {
                    if ("SUITABLE".equals(current.decision())) {
                        totalSuitable++;
                        addBounded(suitable, current, limit);
                    } else if ("NEEDS_REVIEW".equals(current.decision())) {
                        totalReview++;
                        addBounded(review, current, limit);
                    }
                }
            }
            cursor = ids.getLast();
        }
        int remaining = Math.max(0, limit - suitable.size());
        return new ShortlistDTO(List.copyOf(suitable), List.copyOf(review.subList(0,
                Math.min(remaining, review.size()))), totalSuitable, totalReview);
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
        return evaluateLocked(vacancy, source, profile, policyVersion, revision);
    }

    private boolean evaluateLocked(VacancyReadDTO vacancy, SourceEvidenceDTO source, LoadedProfileDTO profile,
                                   String policyVersion, String revision) {
        long id = vacancy.id();
        SelectedExtractionDTO selected = select(policyVersion, id, source.sourceItemId(), revision);
        Long workId = selected == null ? null : selected.workId();
        String basis = selected == null ? "NONE" : selected.status();
        if (evaluations.current(id, revision, profile, policyVersion, workId, basis) != null) {
            return false;
        }
        VacancyEvaluationDTO result = ExtractionAwareEvaluationPolicy.VERSION.equals(policyVersion)
                ? extractionPolicy.evaluate(vacancy, source, profile, revision, selected)
                : policy.evaluate(vacancy, source, profile, revision);
        return evaluations.insert(result, profile, policyVersion, selected);
    }

    private VacancyEvaluationDTO current(long id, LoadedProfileDTO profile, String policyVersion,
                                         boolean reportOnly) {
        VacancyReadDTO vacancy = vacancies.lock(id);
        if (vacancy == null) {
            return null;
        }
        if (reportOnly && !trustedLiveSource(vacancy.sourceNamespace())) {
            return null;
        }
        SourceEvidenceDTO source = sources.latestForVacancy(id);
        if (source == null) {
            return null;
        }
        if (reportOnly && !trustedLiveEvidence(vacancy.sourceNamespace(), source.inputVersion())) {
            return null;
        }
        String revision = policy.revision(vacancy, source);
        SelectedExtractionDTO selected = select(policyVersion, id, source.sourceItemId(), revision);
        return evaluations.current(id, revision, profile, policyVersion,
                selected == null ? null : selected.workId(), selected == null ? "NONE" : selected.status());
    }

    private static boolean trustedLiveSource(String namespace) {
        return namespace != null && (namespace.startsWith("greenhouse:") || namespace.startsWith("lever:"));
    }

    private static boolean trustedLiveEvidence(String namespace, String version) {
        return namespace.startsWith("greenhouse:") && "greenhouse-board-v1".equals(version)
                || namespace.startsWith("lever:") && "lever-postings-v1".equals(version);
    }

    private SelectedExtractionDTO select(String version, long vacancyId, long sourceId, String revision) {
        if (!ExtractionAwareEvaluationPolicy.VERSION.equals(version)) {
            return null;
        }
        return extractionEvidence.current(vacancyId, sourceId, revision);
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
