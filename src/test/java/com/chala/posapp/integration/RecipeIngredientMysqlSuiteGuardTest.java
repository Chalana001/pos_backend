package com.chala.posapp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-skip guard for {@link RecipeIngredientMysqlIntegrationTest}, reusing
 * the TASK-008A guard convention.
 *
 * <p>Contract being enforced: with Docker available, the MySQL verification
 * suite CANNOT silently skip. The suite's only skip path is
 * {@code @Testcontainers(disabledWithoutDocker = true)}, and that path fires
 * exactly when Docker is absent — the accepted "verification pending"
 * situation. While Docker is up, any disabling annotation or condition added
 * to the suite later would turn a real verification into a silent green
 * build; this guard fails instead.
 *
 * <p>When Docker is absent this guard ABORTS (JUnit reports it skipped) —
 * Docker-less environments must report MySQL verification as PENDING, never
 * as passed. The guard itself never fabricates a pass for the suite.
 */
class RecipeIngredientMysqlSuiteGuardTest {

    @Test
    @DisplayName("with Docker available the recipe MySQL suite has no path left to skip silently")
    void mySqlSuiteCannotSkipWhileDockerIsAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker unavailable — recipe ingredient MySQL verification is PENDING, not passed");

        Class<?> suite = RecipeIngredientMysqlIntegrationTest.class;

        // The suite must not be disabled at class level.
        assertThat(suite.isAnnotationPresent(org.junit.jupiter.api.Disabled.class))
                .as("the MySQL suite must never be @Disabled — Docker is available, it must run")
                .isFalse();
        assertThat(suite.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class))
                .as("the MySQL suite must not carry @DisabledIf")
                .isFalse();

        // Its only skip mechanism must be Testcontainers' Docker-absence
        // switch, which by definition does not fire while Docker is up.
        TestcontainersMarker marker = readTestcontainers(suite);
        assertThat(marker).as("the MySQL suite must be annotated @Testcontainers").isNotNull();
        assertThat(marker.disabledWithoutDocker())
                .as("skip may only be tied to Docker absence, nothing else")
                .isTrue();

        // No test method may carry its own disabling annotation either.
        for (Method method : suite.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) {
                continue;
            }
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.Disabled.class))
                    .as("test %s must not be @Disabled", method.getName())
                    .isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class))
                    .as("test %s must not carry @DisabledIf", method.getName())
                    .isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIf.class))
                    .as("test %s must not carry @EnabledIf", method.getName())
                    .isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable.class))
                    .as("test %s must not carry @EnabledIfEnvironmentVariable", method.getName())
                    .isFalse();
        }
    }

    /** The two facts this guard needs out of the @Testcontainers annotation. */
    private record TestcontainersMarker(boolean disabledWithoutDocker) {
    }

    private TestcontainersMarker readTestcontainers(Class<?> suite) {
        org.testcontainers.junit.jupiter.Testcontainers annotation =
                suite.getAnnotation(org.testcontainers.junit.jupiter.Testcontainers.class);
        return annotation == null
                ? null
                : new TestcontainersMarker(annotation.disabledWithoutDocker());
    }
}
