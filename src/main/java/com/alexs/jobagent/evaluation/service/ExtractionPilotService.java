package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.dto.ExtractionBudgetDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionCompletionDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionGatewayResponseDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionResultDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionUsageDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionWorkIdentityDTO;
import com.alexs.jobagent.evaluation.exception.ExtractionFailureException;
import com.alexs.jobagent.evaluation.exception.ExtractionValidationException;
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
    private static final String UNCERTAIN = "UNCERTAIN";
    private static final String SUCCEEDED = "SUCCEEDED";
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
            ExtractionRepository.WorkRow work = repository.find(identity(input, model));
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
        PreparedRequest request = prepare(vacancyId, comparison, liveConfirmed, pricingReviewedOn);
        Reservation reservation = transactions.execute(status -> reserve(request.input(), comparison,
                replayUncertain, request.model(), request.estimatedCost()));
        if (reservation.reused() != null) {
            return reservation.reused();
        }
        return execute(request, reservation);
    }

    private PreparedRequest prepare(long vacancyId, boolean comparison, boolean liveConfirmed,
                                    String pricingReviewedOn) {
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
        return new PreparedRequest(input, model, estimatedCost, key);
    }

    private ExtractionResultDTO execute(PreparedRequest request, Reservation reservation) {
        ExtractionGatewayResponseDTO response = null;
        ExtractionOutputDTO output;
        long actual;
        try {
            cancellation.check();
            response = gateway.extract(request.input(), request.model(), request.key());
            output = validator.validate(response.content(), request.input());
            actual = ExtractionPolicy.cost(request.model(), response.inputTokens(), response.cacheCreationTokens(),
                    response.cacheReadTokens(), response.outputTokens());
            if (actual > reservation.amount()) {
                throw new ExtractionFailureException("OVER_ESTIMATE", "Provider usage exceeds reservation");
            }
        } catch (RuntimeException original) {
            recordFailure(request.model(), reservation, response, original);
            throw original;
        }
        return completeSuccess(request, reservation, response, output, actual);
    }

    private ExtractionResultDTO completeSuccess(PreparedRequest request, Reservation reservation,
                                                ExtractionGatewayResponseDTO response, ExtractionOutputDTO output,
                                                long actual) {
        ExtractionUsageDTO usage = new ExtractionUsageDTO(response.inputTokens(), response.cacheCreationTokens(),
                response.cacheReadTokens(), response.outputTokens(), actual, response.requestId());
        // A failed acknowledgement leaves the durable global claim for controlled recovery.
        transactions.executeWithoutResult(status -> repository.finish(new ExtractionCompletionDTO(
                reservation.workId(), reservation.token(), SUCCEEDED, json.writeValueAsString(output),
                SUCCEEDED, usage, reservation.amount())));
        return new ExtractionResultDTO(reservation.workId(), SUCCEEDED, request.model(),
                request.input().sourceItemId(), request.input().revision(), output, false);
    }

    private void recordFailure(String model, Reservation reservation, ExtractionGatewayResponseDTO response,
                               RuntimeException original) {
        String outcome = original instanceof ExtractionFailureException failure ? failure.outcome() : "INVALID";
        String state = UNCERTAIN.equals(outcome) || "CANCELLED".equals(outcome)
                || "OVER_ESTIMATE".equals(outcome) ? UNCERTAIN : "FAILED";
        ExtractionUsageDTO usage = failureUsage(model, response, original);
        boolean interrupted = Thread.currentThread().isInterrupted();
        if (interrupted) {
            Thread.interrupted();
        }
        ExtractionGatewayResponseDTO returnedResponse = response;
        try {
            transactions.executeWithoutResult(status -> {
                repository.finish(new ExtractionCompletionDTO(reservation.workId(), reservation.token(),
                        state, null, outcome, usage, reservation.amount()));
                if (returnedResponse != null && original instanceof ExtractionValidationException validation) {
                    repository.recordRejectedOutput(reservation.workId(), reservation.token(),
                            validation.claimIndex(), validation.category(), returnedResponse.content());
                }
            });
        } catch (RuntimeException trackingFailure) {
            original.addSuppressed(trackingFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ExtractionUsageDTO failureUsage(String model, ExtractionGatewayResponseDTO response,
                                                   RuntimeException original) {
        Integer input = null;
        Integer cacheCreation = null;
        Integer cacheRead = null;
        Integer output = null;
        String requestId = null;
        if (response != null) {
            input = response.inputTokens();
            cacheCreation = response.cacheCreationTokens();
            cacheRead = response.cacheReadTokens();
            output = response.outputTokens();
            requestId = response.requestId();
        } else if (original instanceof ExtractionFailureException failure) {
            input = failure.inputTokens();
            cacheCreation = failure.cacheCreationTokens();
            cacheRead = failure.cacheReadTokens();
            output = failure.outputTokens();
            requestId = failure.requestId();
        }
        Long observed = reportedCost(model, input, cacheCreation, cacheRead, output);
        return new ExtractionUsageDTO(input, cacheCreation, cacheRead, output, observed, requestId);
    }

    private static Long reportedCost(String model, Integer input, Integer cacheCreation,
                                     Integer cacheRead, Integer output) {
        if (input == null || cacheCreation == null || cacheRead == null || output == null) {
            return null;
        }
        try {
            return ExtractionPolicy.cost(model, input, cacheCreation, cacheRead, output);
        } catch (IllegalArgumentException ignored) {
            // Malformed provider usage retains the full reservation.
            return null;
        }
    }

    private Reservation reserve(ExtractionInputDTO input, boolean comparison, boolean replayUncertain,
                                String model, long amount) {
        ExtractionRepository.BudgetRow budget = repository.lockBudget();
        ExtractionInputDTO current = inputs.loadLocked(input.vacancyId());
        requireSameSnapshot(input, current);
        ExtractionWorkIdentityDTO identity = identity(input, model);
        ExtractionRepository.WorkRow work = repository.find(identity);
        String inputJson = json.writeValueAsString(input.sourceFields());
        if (work != null && !json.readTree(work.inputJson()).equals(json.readTree(inputJson))) {
            throw new IllegalStateException("Extraction input changed without a version update");
        }
        if (work != null && SUCCEEDED.equals(work.status())) {
            ExtractionOutputDTO output = json.readValue(work.resultJson(), ExtractionOutputDTO.class);
            return new Reservation(work.id(), null, 0, new ExtractionResultDTO(work.id(), SUCCEEDED, model,
                    input.sourceItemId(), input.revision(), output, true));
        }
        requireAvailable(budget);
        if (comparison && !repository.primarySucceeded(identity(input, ExtractionPolicy.PRIMARY_MODEL), inputJson)) {
            throw new IllegalStateException("Comparison requires a successful primary extraction of this revision");
        }
        requireReplayAllowed(work, replayUncertain);
        requireBudget(budget, work, amount);
        boolean distinct = work == null;
        if (distinct && (comparison ? budget.comparisonDistinct() >= 10 : budget.primaryDistinct() >= 30)) {
            throw new IllegalStateException("Extraction distinct revision cap reached");
        }
        UUID token = UUID.randomUUID();
        long workId;
        if (distinct) {
            workId = repository.insertWork(identity, comparison ? "COMPARISON" : "PRIMARY", inputJson, token);
        } else {
            workId = work.id();
            repository.claimAgain(workId, token);
        }
        repository.reserve(workId, token, amount, !comparison, distinct);
        return new Reservation(workId, token, amount, null);
    }

    private static void requireSameSnapshot(ExtractionInputDTO input, ExtractionInputDTO current) {
        if (!current.revision().equals(input.revision()) || current.sourceItemId() != input.sourceItemId()
                || !current.sourceFields().equals(input.sourceFields())) {
            throw new IllegalStateException("Vacancy snapshot changed before extraction reservation");
        }
    }

    private static void requireAvailable(ExtractionRepository.BudgetRow budget) {
        if (budget.haltedReason() != null) {
            throw new IllegalStateException("Extraction pilot halted for spending review");
        }
        if (budget.activeWorkId() != null) {
            throw new IllegalStateException("Global extraction claim requires completion or controlled recovery");
        }
    }

    private static void requireReplayAllowed(ExtractionRepository.WorkRow work, boolean replayUncertain) {
        if (work != null && "CLAIMED".equals(work.status())) {
            throw new IllegalStateException("Unowned extraction claim requires review");
        }
        if (work != null && UNCERTAIN.equals(work.status()) && !replayUncertain) {
            throw new IllegalStateException("Uncertain extraction requires controlled replay");
        }
    }

    private void requireBudget(ExtractionRepository.BudgetRow budget, ExtractionRepository.WorkRow work, long amount) {
        if (work != null && repository.attemptCount(work.id()) >= ExtractionPolicy.MAX_ATTEMPTS_PER_WORK) {
            throw new IllegalStateException("Extraction retry limit reached");
        }
        if (budget.attempts() >= 80 || amount > ExtractionPolicy.TARGET_MICRO_USD - budget.reservedMicroUsd()) {
            throw new IllegalStateException("Extraction pilot attempt or USD target reached");
        }
    }

    private static ExtractionWorkIdentityDTO identity(ExtractionInputDTO input, String model) {
        return new ExtractionWorkIdentityDTO(input.vacancyId(), input.sourceItemId(), input.revision(), model,
                ExtractionPolicy.PROMPT_VERSION, ExtractionPolicy.SCHEMA_VERSION, ExtractionPolicy.POLICY_VERSION);
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
    private record PreparedRequest(ExtractionInputDTO input, String model, long estimatedCost, String key) { }
}
