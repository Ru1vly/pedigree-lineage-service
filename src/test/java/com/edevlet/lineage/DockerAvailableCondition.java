package com.edevlet.lineage;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips (rather than fails) a Testcontainers-based test when no usable Docker environment is
 * detected. JUnit evaluates ExecutionConditions before any other extension callback runs for the
 * class, so this runs - and can skip the class - before @Testcontainers' own beforeAll attempts
 * to start any container. Not every environment this suite runs in is guaranteed to have a
 * working Docker daemon (or, as encountered during development, one that behaves like one);
 * skipping gracefully there is the standard Testcontainers pattern, not a weakening of the test -
 * it still runs for real wherever Docker is actually usable.
 */
public class DockerAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
        } catch (Throwable ignored) {
            // fall through to disabled
        }
        return ConditionEvaluationResult.disabled(
                "No usable Docker environment detected - skipping Testcontainers-based test");
    }
}
