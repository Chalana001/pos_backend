package com.chala.posapp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents silent MySQL-suite skips while Docker is available. */
class ProcessingOutputMysqlSuiteGuardTest {

    @Test
    void processingOutputMysqlSuiteCannotSkipWhileDockerIsAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker unavailable — processing-output MySQL verification is PENDING, not passed");

        Class<?> suite = ProcessingOutputMysqlIntegrationTest.class;
        assertThat(suite.isAnnotationPresent(Disabled.class)).isFalse();
        assertThat(suite.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class)).isFalse();

        Testcontainers annotation = suite.getAnnotation(Testcontainers.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.disabledWithoutDocker()).isTrue();

        for (Method method : suite.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Test.class)) {
                continue;
            }
            assertThat(method.isAnnotationPresent(Disabled.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.DisabledIf.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIf.class)).isFalse();
            assertThat(method.isAnnotationPresent(org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable.class)).isFalse();
        }
    }
}
