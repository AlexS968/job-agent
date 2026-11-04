package com.alexs.jobagent;

import com.alexs.jobagent.outbox.service.SqsConsumerService;
import com.alexs.jobagent.outbox.service.SqsProducerService;
import com.alexs.jobagent.outbox.service.SqsEvaluationConsumerService;
import com.alexs.jobagent.evaluation.service.ProfileFileService;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/** Explicit one-off queue commands. SQS beans are resolved only for a selected SQS mode. */
final class SqsBatchCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqsBatchCommands.class);
    private static final String LIMIT_OPTION = "--limit=";
    private static final String EVENT_ID_OPTION = "--event-id=";
    private final ApplicationContext context;

    SqsBatchCommands(ApplicationContext context) {
        this.context = context;
    }

    void produce(String[] args) {
        int limit = limit(args, "--mode=sqs-produce", 20);
        var result = context.getBean(SqsProducerService.class).publish(limit);
        LOGGER.info("SQS publish invocation: sent={}, deferred={}, failed={}",
                result.processed(), result.deferred(), result.failed());
        if (result.deferred() > 0 || result.failed() > 0) {
            throw new IllegalStateException("SQS publish left deferred or failed events");
        }
    }

    void consume(String[] args) {
        int limit = limit(args, "--mode=sqs-consume", 1);
        var result = context.getBean(SqsConsumerService.class).consume(limit);
        LOGGER.info("SQS consume invocation: processed={}, duplicates={}, failed={}",
                result.processed(), result.duplicates(), result.failed());
        if (result.failed() > 0) {
            throw new IllegalStateException("SQS consume left retryable messages");
        }
    }

    void evaluate(String[] args) {
        int limit = limit(args, "--mode=sqs-evaluate", 1);
        String profileJson = context.getEnvironment().getProperty("JOBAGENT_EVALUATION_PROFILE_JSON");
        var profile = context.getBean(ProfileFileService.class).loadJson(profileJson);
        var result = context.getBean(SqsEvaluationConsumerService.class).consume(limit, profile);
        LOGGER.info("SQS evaluation invocation: evaluated={}, duplicates={}, stale={}, failed={}",
                result.evaluated(), result.duplicates(), result.stale(), result.failed());
        if (result.failed() > 0) {
            throw new IllegalStateException("SQS evaluation left retryable messages");
        }
    }

    void evaluationStatus(String[] args) {
        if (args.length != 2 || Arrays.stream(args).filter("--mode=sqs-evaluation-status"::equals).count() != 1) {
            throw new IllegalArgumentException("SQS evaluation status requires one event ID");
        }
        String option = Arrays.stream(args).filter(arg -> arg.startsWith(EVENT_ID_OPTION))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("SQS evaluation status requires one event ID"));
        UUID eventId = UUID.fromString(option.substring(EVENT_ID_OPTION.length()));
        var profile = context.getBean(ProfileFileService.class)
                .loadJson(context.getEnvironment().getProperty("JOBAGENT_EVALUATION_PROFILE_JSON"));
        var result = context.getBean(SqsEvaluationConsumerService.class).status(eventId, profile);
        if (result.evaluation() == null) {
            LOGGER.info("SQS evaluation status: event_id={}, outcome={}", eventId, result.outcome());
        } else {
            var evaluation = result.evaluation();
            LOGGER.info("SQS evaluation status: event_id={}, outcome={}, evaluation_id={}, vacancy_id={}, "
                            + "source_item_id={}, revision={}, profile_version={}, "
                            + "policy_version={}, decision={}, reasons={}, unknowns={}",
                    eventId, result.outcome(), evaluation.id(), evaluation.vacancyId(),
                    evaluation.sourceItemId(), evaluation.revision(), evaluation.profileVersion(),
                    evaluation.policyVersion(), evaluation.decision(), evaluation.reasons(), evaluation.unknowns());
        }
    }

    void status(String[] args) {
        if (args.length != 2 || Arrays.stream(args).filter("--mode=sqs-status"::equals).count() != 1) {
            throw new IllegalArgumentException("SQS status requires one event ID");
        }
        String option = Arrays.stream(args).filter(arg -> arg.startsWith(EVENT_ID_OPTION))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("SQS status requires one event ID"));
        UUID eventId = UUID.fromString(option.substring(EVENT_ID_OPTION.length()));
        var result = context.getBean(SqsConsumerService.class).status(eventId);
        LOGGER.info("SQS diagnostic status: event_id={}, receipt_recorded={}, observation_recorded={}",
                eventId, result.receiptRecorded(), result.observationRecorded());
    }

    private int limit(String[] args, String mode, int fallback) {
        if (Arrays.stream(args).filter(mode::equals).count() != 1) {
            throw new IllegalArgumentException("Invalid SQS command mode");
        }
        String[] options = Arrays.stream(args).filter(arg -> !mode.equals(arg)).toArray(String[]::new);
        if (options.length == 0) {
            return fallback;
        }
        if (options.length != 1 || !options[0].startsWith(LIMIT_OPTION)) {
            throw new IllegalArgumentException("Invalid SQS command limit");
        }
        return Math.toIntExact(Long.parseLong(options[0].substring(LIMIT_OPTION.length())));
    }
}
