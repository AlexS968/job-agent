package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionBudgetDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionGatewayResponseDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionResultDTO;
import com.alexs.jobagent.evaluation.exception.ExtractionFailureException;
import com.alexs.jobagent.evaluation.repository.ExtractionRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExtractionPilotService {
    private final ExtractionInputService inputs;
    private final ExtractionOutputValidator validator;
    private final AnthropicExtractionGateway gateway;
    private final ExtractionRepository repository;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Supplier<String> credential;
    private final ExtractionCostEstimator costEstimator;
    private final ExtractionCancellationGuard cancellation;

    @Autowired
    public ExtractionPilotService(ExtractionInputService inputs, ExtractionOutputValidator validator,
                                  AnthropicExtractionGateway gateway, ExtractionRepository repository,
                                  JsonMapper json, PlatformTransactionManager manager,
                                  ExtractionCostEstimator costEstimator, ExtractionCancellationGuard cancellation) {
        this(inputs, validator, gateway, repository, json, manager, Clock.systemUTC(),
                () -> System.getenv("ANTHROPIC_API_KEY"), costEstimator, cancellation);
    }

    public ExtractionPilotService(ExtractionInputService inputs, ExtractionOutputValidator validator,
                                  AnthropicExtractionGateway gateway, ExtractionRepository repository,
                                  JsonMapper json, PlatformTransactionManager manager, Clock clock,
                                  Supplier<String> credential, ExtractionCostEstimator costEstimator,
                                  ExtractionCancellationGuard cancellation) {
        this.inputs = inputs;
        this.validator = validator;
        this.gateway = gateway;
        this.repository = repository;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.credential = credential;
        this.costEstimator = costEstimator;
        this.cancellation = cancellation;
    }

    public ExtractionInputDTO plan(long vacancyId, boolean comparison) {
        ExtractionInputDTO input = inputs.load(vacancyId);
        String model = model(comparison);
        if (gateway.requestBody(input, model).length > ExtractionPolicy.MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Extraction request exceeds byte bound");
        }
        return input;
    }

    public ExtractionBudgetDTO budget() {
        return repository.budget();
    }

    public ExtractionResultDTO current(long vacancyId, boolean comparison) {
        String model = model(comparison);
        return transactions.execute(status -> {
            ExtractionInputDTO input = inputs.loadLocked(vacancyId);
            ExtractionRepository.WorkRow work = repository.find(vacancyId, input.sourceItemId(),
                    input.revision(), model, ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION,
                    ExtractionPolicy.POLICY_VERSION);
            if (work == null) {
                return null;
            }
            if (!json.readTree(work.inputJson()).equals(json.readTree(json.writeValueAsString(input.sourceFields())))) {
                throw new IllegalStateException("Extraction input changed without a version update");
            }
            ExtractionOutputDTO output = work.resultJson() == null ? null
                    : json.readValue(work.resultJson(), ExtractionOutputDTO.class);
            return new ExtractionResultDTO(work.id(), work.status(), model, input.sourceItemId(),
                    input.revision(), output, true);
        });
    }

    public ExtractionResultDTO extract(long vacancyId, boolean comparison, boolean replayUncertain,
                                       boolean liveConfirmed, String pricingReviewedOn) {
        cancellation.check();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Extraction cannot join an ambient transaction");
        }
        if (!liveConfirmed || !ExtractionPolicy.PRICE_REVIEW_DATE.toString().equals(pricingReviewedOn)
                || !clock.instant().atZone(clock.getZone()).toLocalDate().equals(ExtractionPolicy.PRICE_REVIEW_DATE)) {
            throw new IllegalStateException("Live extraction requires current pricing review and explicit opt-in");
        }
        ExtractionInputDTO input = plan(vacancyId, comparison);
        String model = model(comparison);
        cancellation.check();
        long estimatedCost = costEstimator.estimatedMicroUsd(input, model);
        if (estimatedCost <= 0 || estimatedCost > ExtractionPolicy.TARGET_MICRO_USD) {
            throw new IllegalStateException("Extraction estimate exceeds the pilot target");
        }
        String key = credential.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Anthropic credential is not configured");
        }
        Reservation reservation = transactions.execute(status -> reserve(input, comparison, replayUncertain,
                model, estimatedCost));
        if (reservation.reused() != null) {
            return reservation.reused();
        }
        ExtractionGatewayResponseDTO response = null;
        ExtractionOutputDTO output;
        long actual;
        try {
            cancellation.check();
            response = gateway.extract(input, model, key);
            output = validator.validate(response.content(), input);
            actual = ExtractionPolicy.cost(model, response.inputTokens(), response.cacheCreationTokens(),
                    response.cacheReadTokens(), response.outputTokens());
            if (actual > reservation.amount()) {
                throw new ExtractionFailureException("OVER_ESTIMATE", "Provider usage exceeds reservation");
            }
        } catch (RuntimeException original) {
            String outcome = original instanceof ExtractionFailureException failure ? failure.outcome() : "INVALID";
            String state = "UNCERTAIN".equals(outcome) || "CANCELLED".equals(outcome)
                    || "OVER_ESTIMATE".equals(outcome) ? "UNCERTAIN" : "FAILED";
            Integer inputTokens = response != null ? response.inputTokens()
                    : original instanceof ExtractionFailureException failure ? failure.inputTokens() : null;
            Integer cacheCreationTokens = response != null ? response.cacheCreationTokens()
                    : original instanceof ExtractionFailureException failure ? failure.cacheCreationTokens() : null;
            Integer cacheReadTokens = response != null ? response.cacheReadTokens()
                    : original instanceof ExtractionFailureException failure ? failure.cacheReadTokens() : null;
            Integer outputTokens = response != null ? response.outputTokens()
                    : original instanceof ExtractionFailureException failure ? failure.outputTokens() : null;
            String requestId = response != null ? response.requestId()
                    : original instanceof ExtractionFailureException failure ? failure.requestId() : null;
            Long reportedCost = null;
            if (inputTokens != null && cacheCreationTokens != null && cacheReadTokens != null
                    && outputTokens != null) {
                try {
                    reportedCost = ExtractionPolicy.cost(model, inputTokens, cacheCreationTokens,
                            cacheReadTokens, outputTokens);
                } catch (IllegalArgumentException ignored) {
                    // Unsupported model or malformed usage retains the full reservation.
                }
            }
            Long recordedCost = reportedCost;
            boolean interrupted = Thread.currentThread().isInterrupted();
            if (interrupted) {
                Thread.interrupted();
            }
            try {
                transactions.executeWithoutResult(status -> repository.finish(reservation.workId(), reservation.token(),
                        state, null, outcome, inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens,
                        recordedCost, requestId, reservation.amount()));
            } catch (RuntimeException trackingFailure) {
                original.addSuppressed(trackingFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw original;
        }
        ExtractionGatewayResponseDTO completed = response;
        long billed = actual;
        ExtractionOutputDTO validated = output;
        // A failed acknowledgement stays CLAIMED until expiry makes it UNCERTAIN.
        transactions.executeWithoutResult(status -> repository.finish(reservation.workId(), reservation.token(),
                "SUCCEEDED", json.writeValueAsString(validated), "SUCCEEDED", completed.inputTokens(),
                completed.cacheCreationTokens(), completed.cacheReadTokens(), completed.outputTokens(),
                billed, completed.requestId(), reservation.amount()));
        return new ExtractionResultDTO(reservation.workId(), "SUCCEEDED", model, input.sourceItemId(),
                input.revision(), output, false);
    }

    private Reservation reserve(ExtractionInputDTO input, boolean comparison, boolean replayUncertain,
                                String model, long amount) {
        ExtractionRepository.BudgetRow budget = repository.lockBudget();
        ExtractionInputDTO current = inputs.loadLocked(input.vacancyId());
        if (!current.revision().equals(input.revision())
                || current.sourceItemId() != input.sourceItemId()
                || !current.sourceFields().equals(input.sourceFields())) {
            throw new IllegalStateException("Vacancy snapshot changed before extraction reservation");
        }
        ExtractionRepository.WorkRow work = repository.find(input.vacancyId(), input.sourceItemId(),
                input.revision(), model, ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION,
                ExtractionPolicy.POLICY_VERSION);
        String inputJson = json.writeValueAsString(input.sourceFields());
        if (work != null && !json.readTree(work.inputJson()).equals(json.readTree(inputJson))) {
            throw new IllegalStateException("Extraction input changed without a version update");
        }
        if (work != null && "SUCCEEDED".equals(work.status())) {
            ExtractionOutputDTO output = json.readValue(work.resultJson(), ExtractionOutputDTO.class);
            return new Reservation(work.id(), null, 0, new ExtractionResultDTO(work.id(), "SUCCEEDED", model,
                    input.sourceItemId(), input.revision(), output, true));
        }
        if (budget.haltedReason() != null) {
            throw new IllegalStateException("Extraction pilot halted for spending review");
        }
        if (budget.activeWorkId() != null) {
            throw new IllegalStateException("Global extraction claim requires completion or controlled recovery");
        }
        if (comparison && !repository.primarySucceeded(input.vacancyId(), input.sourceItemId(),
                input.revision(), ExtractionPolicy.PRIMARY_MODEL, ExtractionPolicy.PROMPT_VERSION,
                ExtractionPolicy.SCHEMA_VERSION, ExtractionPolicy.POLICY_VERSION, inputJson)) {
            throw new IllegalStateException("Comparison requires a successful primary extraction of this revision");
        }
        if (work != null && "CLAIMED".equals(work.status())) {
            throw new IllegalStateException("Unowned extraction claim requires review");
        }
        if (work != null && "UNCERTAIN".equals(work.status()) && !replayUncertain) {
            throw new IllegalStateException("Uncertain extraction requires controlled replay");
        }
        if (work != null && repository.attemptCount(work.id()) >= ExtractionPolicy.MAX_ATTEMPTS_PER_WORK) {
            throw new IllegalStateException("Extraction retry limit reached");
        }
        if (budget.attempts() >= 80 || amount > ExtractionPolicy.TARGET_MICRO_USD - budget.reservedMicroUsd()) {
            throw new IllegalStateException("Extraction pilot attempt or USD target reached");
        }
        boolean distinct = work == null;
        if (distinct && (comparison ? budget.comparisonDistinct() >= 10 : budget.primaryDistinct() >= 30)) {
            throw new IllegalStateException("Extraction distinct revision cap reached");
        }
        UUID token = UUID.randomUUID();
        long workId;
        if (distinct) {
            workId = repository.insertWork(input.vacancyId(), input.sourceItemId(), input.revision(), model,
                    ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION,
                    ExtractionPolicy.POLICY_VERSION, comparison ? "COMPARISON" : "PRIMARY",
                    inputJson, token);
        } else {
            workId = work.id();
            repository.claimAgain(workId, token);
        }
        repository.reserve(workId, token, amount, !comparison, distinct);
        return new Reservation(workId, token, amount, null);
    }

    private static String model(boolean comparison) {
        return comparison ? ExtractionPolicy.COMPARISON_MODEL : ExtractionPolicy.PRIMARY_MODEL;
    }

    public void recoverGlobalClaim(long workId, boolean confirmedNoInFlight,
                                   boolean billingReviewed) {
        if (!confirmedNoInFlight || !billingReviewed) {
            throw new IllegalArgumentException("Recovery requires explicit request and billing review");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recovery cannot join an ambient transaction");
        }
        transactions.executeWithoutResult(status -> {
            ExtractionRepository.BudgetRow budget = repository.lockBudget();
            if (budget.activeWorkId() == null || budget.activeWorkId() != workId) {
                throw new IllegalStateException("No matching global extraction claim");
            }
            repository.recover(workId, budget.activeToken());
        });
    }

    private record Reservation(long workId, UUID token, long amount, ExtractionResultDTO reused) { }
}
