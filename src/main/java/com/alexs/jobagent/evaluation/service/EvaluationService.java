package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.EvaluationBatchResultDTO;
import com.alexs.jobagent.evaluation.dto.EvaluationInputRefreshDTO;
import com.alexs.jobagent.evaluation.dto.LoadedProfileDTO;
import com.alexs.jobagent.evaluation.dto.ShortlistDTO;
import com.alexs.jobagent.evaluation.dto.VacancyEvaluationDTO;
import com.alexs.jobagent.evaluation.dto.SelectedExtractionDTO;
import com.alexs.jobagent.evaluation.dto.EvaluationStatusDTO;
import com.alexs.jobagent.evaluation.repository.EvaluationRepository;
import com.alexs.jobagent.source.dto.SourceEvidenceDTO;
import com.alexs.jobagent.source.service.HimalayasIntakeService;
import com.alexs.jobagent.source.service.HimalayasPostingEvidence;
import com.alexs.jobagent.source.service.SourceEvidenceService;
import com.alexs.jobagent.vacancy.dto.VacancyReadDTO;
import com.alexs.jobagent.vacancy.service.VacancyReadService;
import com.alexs.jobagent.vacancy.service.CandidateAssessmentService;
import java.time.Clock;
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
    private static final int MAX_REPORT_CANDIDATES = 500;
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
    private final CountryEvidencePolicy countryEvidence;
    private final CandidateAssessmentService candidates;
    private final HimalayasPostingEvidence himalayasEvidence;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public EvaluationService(VacancyReadService vacancies, SourceEvidenceService sources,
                             EvaluationRepository evaluations, RuleEvaluationPolicy policy,
                             ExtractionAwareEvaluationPolicy extractionPolicy,
                             ExtractionEvidenceService extractionEvidence, CountryEvidencePolicy countryEvidence,
                             CandidateAssessmentService candidates, HimalayasPostingEvidence himalayasEvidence, Clock clock,
                             PlatformTransactionManager manager) {
        this.vacancies = vacancies;
        this.sources = sources;
        this.evaluations = evaluations;
        this.policy = policy;
        this.extractionPolicy = extractionPolicy;
        this.extractionEvidence = extractionEvidence;
        this.countryEvidence = countryEvidence;
        this.candidates = candidates;
        this.himalayasEvidence = himalayasEvidence;
        this.clock = clock;
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

    /** All current, source-backed country recommendations for a bounded publication snapshot. */
    public List<VacancyEvaluationDTO> reportCandidates(LoadedProfileDTO profile, String policyVersion) {
        rejectAmbient();
        if (!validVersion(policyVersion)) {
            throw new IllegalArgumentException("Invalid report policy version");
        }
        evaluations.requireRegisteredProfile(profile);
        List<VacancyEvaluationDTO> candidates = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<Long> ids = vacancies.idsAfter(cursor, PAGE_SIZE);
            if (ids.isEmpty()) {
                return List.copyOf(candidates);
            }
            for (long id : ids) {
                VacancyEvaluationDTO candidate = transaction.execute(status -> current(id, profile,
                        policyVersion, true));
                if (candidate != null && !"UNMATCHED".equals(candidate.roleClassification())
                        && evaluations.countryConfirmed(candidate, profile, policyVersion)
                        && ("SUITABLE".equals(candidate.decision())
                        || "NEEDS_REVIEW".equals(candidate.decision()))) {
                    candidates.add(candidate);
                    if (candidates.size() > MAX_REPORT_CANDIDATES) {
                        throw new IllegalStateException("Report candidate limit exceeded; review before publishing");
                    }
                }
            }
            cursor = ids.getLast();
        }
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
                if (current != null && (!reportOnly || !"UNMATCHED".equals(current.roleClassification())
                        && evaluations.countryConfirmed(current, profile, policyVersion))) {
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
        recordInput(vacancy, source, revision);
        SelectedExtractionDTO selected = select(policyVersion, id, source.sourceItemId(), revision);
        Long workId = selected == null ? null : selected.workId();
        String basis = selected == null ? "NONE" : selected.status();
        boolean created = false;
        if (evaluations.current(id, revision, profile, policyVersion, workId, basis) == null) {
            VacancyEvaluationDTO result;
            if (ExtractionAwareEvaluationPolicy.VERSION.equals(policyVersion)) {
                result = extractionPolicy.evaluate(vacancy, source, profile, revision, selected);
            } else if (RuleEvaluationPolicy.VERSION.equals(policyVersion)
                    && (trustedLiveSource(vacancy.sourceNamespace())
                    || isHimalayas(vacancy, source))) {
                result = policy.evaluateCandidate(vacancy, source, profile, revision,
                        candidates.forEvaluation(source.sourceItemId(), vacancy));
            } else {
                result = policy.evaluate(vacancy, source, profile, revision);
            }
            created = evaluations.insert(result, profile, policyVersion, selected);
        }
        var country = countryEvidence.assess(vacancy, source, profile.profile().workCountry());
        evaluations.recordCountryEvidence(id, revision, profile, policyVersion, workId, basis,
                country.status(), country.evidence());
        return created;
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
        var metadata = isHimalayas(vacancy, source)
                ? himalayasEvidence.reviewMetadata(source.rawPayload(), clock.instant()) : null;
        if (metadata != null && metadata.expired()) {
            return null;
        }
        String revision = policy.revision(vacancy, source);
        SelectedExtractionDTO selected = select(policyVersion, id, source.sourceItemId(), revision);
        var result = evaluations.current(id, revision, profile, policyVersion,
                selected == null ? null : selected.workId(), selected == null ? "NONE" : selected.status());
        return result == null || metadata == null ? result
                : result.withSourceAttribution("Himalayas", metadata.applicationLink());
    }

    private void recordInput(VacancyReadDTO vacancy, SourceEvidenceDTO source, String revision) {
        var metadata = isHimalayas(vacancy, source)
                ? himalayasEvidence.reviewMetadata(source.rawPayload(), clock.instant()) : null;
        evaluations.recordInput(vacancy.id(), source.sourceItemId(), revision, source.originalUrl(),
                metadata == null ? null : metadata.applicationLink(),
                metadata == null ? null : himalayasEvidence.expiry(source.rawPayload()));
    }

    /** Bounded, resumable local/database-only projection refresh; does not evaluate or enqueue work. */
    public EvaluationInputRefreshDTO refreshInputs(long afterId, int maximum) {
        rejectAmbient();
        if (afterId < 0 || maximum < 1 || maximum > MAX_BATCH) {
            throw new IllegalArgumentException("Invalid input refresh bounds");
        }
        List<Long> ids = vacancies.idsAfter(afterId, maximum);
        int refreshed = 0;
        long cursor = afterId;
        for (long id : ids) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Input refresh interrupted");
            }
            boolean present = Boolean.TRUE.equals(transaction.execute(status -> {
                VacancyReadDTO vacancy = vacancies.lock(id);
                SourceEvidenceDTO source = vacancy == null ? null : sources.latestForVacancy(id);
                if (source == null) {
                    return false;
                }
                recordInput(vacancy, source, policy.revision(vacancy, source));
                return true;
            }));
            if (present) {
                refreshed++;
            }
            cursor = id;
        }
        return new EvaluationInputRefreshDTO(ids.size(), refreshed, ids.size() - refreshed, cursor,
                !vacancies.idsAfter(cursor, 1).isEmpty());
    }

    private static boolean isHimalayas(VacancyReadDTO vacancy, SourceEvidenceDTO source) {
        return HimalayasIntakeService.NAMESPACE.equals(vacancy.sourceNamespace())
                && HimalayasIntakeService.INPUT_VERSION.equals(source.inputVersion());
    }

    private static boolean trustedLiveSource(String namespace) {
        return namespace != null && (namespace.startsWith("greenhouse:") || namespace.startsWith("lever:")
                || namespace.equals("linkedin"));
    }

    private static boolean trustedLiveEvidence(String namespace, String version) {
        return namespace.startsWith("greenhouse:") && "greenhouse-board-v1".equals(version)
                || namespace.startsWith("lever:") && "lever-postings-v1".equals(version)
                || namespace.equals("linkedin") && "linkedin-alert-v1".equals(version);
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
