package com.chala.posapp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prevents the MySQL candidate-reader suite from gaining a silent skip path.
 * When Docker is unavailable this guard aborts, so MySQL verification remains
 * explicitly pending rather than appearing green.
 */
class PosCatalogueCandidateReaderMysqlSuiteGuardTest {

    @Test
    @DisplayName("with Docker available the MySQL candidate suite cannot skip silently")
    void mysqlSuiteCannotSkipWhileDockerIsAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker unavailable — MySQL candidate verification is PENDING, not passed");

        Class<?> suite = PosCatalogueCandidateReaderMysqlIntegrationTest.class;
        assertThat(suite.isAnnotationPresent(org.junit.jupiter.api.Disabled.class)).isFalse();
        assertThat(suite.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class)).isFalse();

        Testcontainers annotation = suite.getAnnotation(Testcontainers.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.disabledWithoutDocker()).isTrue();

        for (Method method : suite.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) {
                continue;
            }
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.Disabled.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIf.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable.class)).isFalse();
        }
    }
}
