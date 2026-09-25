/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.SearchOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Lets the vulnerability scans that a test queued finish before the test's own cleanup deletes the
 * repo they belong to (RPS-1341).
 *
 * <p>A publish to a repo with the scan on queues a scan on the {@code scanTaskExecutor} ({@code
 * vuln-scan-N} threads). Such a thread reads the scan row, does its work and writes the outcome;
 * when the test's {@code @AfterEach} has deleted the repo in between, the row is gone (the foreign
 * key cascades) and the write fails with {@code ObjectOptimisticLockingFailureException} logged at
 * ERROR, long after the test that caused it, in the middle of another class. This waits for the
 * executor to be idle right after the test method ({@link AfterTestExecutionCallback} runs before
 * the {@code @AfterEach} methods, which is where the cleanup lives).
 *
 * <p>It is cheap: nothing is queued in most tests, and the executor counts {@code taskCount ==
 * completedTaskCount} then. The wait is capped and only logs when it runs out, so a scan that hangs
 * cannot fail or stall the suite.
 *
 * <p>A class that queues scans on purpose and holds them (a scripted scanner behind a gate) opts
 * out with {@link SettlesScansItself}: it releases them in its own cleanup, so waiting here would
 * only burn the cap.
 */
public final class ScanThreadsSettler implements AfterTestExecutionCallback {

  private static final Logger LOG = LoggerFactory.getLogger(ScanThreadsSettler.class);

  private static final String EXECUTOR_BEAN = "scanTaskExecutor";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

  @Override
  public void afterTestExecution(final ExtensionContext context) {
    final var testClass = context.getRequiredTestClass();

    if (AnnotationSupport.isAnnotated(testClass, SettlesScansItself.class)
        || AnnotationSupport.findAnnotation(
                testClass, SettlesScansItself.class, SearchOption.INCLUDE_ENCLOSING_CLASSES)
            .isPresent()) {
      return;
    }

    final var executor =
        SpringExtension.getApplicationContext(context)
            .getBean(EXECUTOR_BEAN, ThreadPoolTaskExecutor.class)
            .getThreadPoolExecutor();
    final var deadline = System.nanoTime() + TIMEOUT.toNanos();

    while (executor.getTaskCount() != executor.getCompletedTaskCount()) {
      if (System.nanoTime() - deadline > 0) {
        LOG.warn(
            "Vulnerability scans of {} are still running after {}",
            context.getDisplayName(),
            TIMEOUT);

        return;
      }

      LockSupport.parkNanos(POLL_NANOS);
    }
  }
}
