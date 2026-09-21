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
package io.repsy.os.shared.user.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thread helpers shared by the PostgreSQL and H2 tests of the last-admin guards (RPS-1101).
 *
 * <p>Every operation under test reports its outcome as a string: {@link #OK}, or the message id of
 * the error it ended with. That keeps the assertions the same whether the operation is a service
 * call or an HTTP request.
 */
final class LastAdminRaces {

  static final String OK = "ok";
  static final int TIMEOUT_SECONDS = 15;

  /**
   * How long an operation has to stay unfinished to count as blocked. An operation that does not
   * take the lock ends within milliseconds, so it does not survive this.
   */
  static final Duration STILL_BLOCKED = Duration.ofSeconds(1);

  private LastAdminRaces() {}

  /**
   * Starts {@code operation} on {@code executor} and waits until it has been running for {@code
   * stillBlocked} without finishing. That works on every database, unlike a look at {@code
   * pg_stat_activity}.
   */
  static Future<String> submitAndExpectBlocked(
      final ExecutorService executor, final Callable<String> operation, final Duration stillBlocked)
      throws InterruptedException {

    final var started = new CountDownLatch(1);
    final var pending =
        executor.submit(
            () -> {
              started.countDown();
              return operation.call();
            });

    assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("operation started").isTrue();
    await()
        .during(stillBlocked)
        .atMost(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .until(() -> !pending.isDone());

    return pending;
  }

  /**
   * {@link #submitAndExpectBlocked(ExecutorService, Callable, Duration)} for {@link
   * #STILL_BLOCKED}.
   */
  static Future<String> submitAndExpectBlocked(
      final ExecutorService executor, final Callable<String> operation)
      throws InterruptedException {

    return submitAndExpectBlocked(executor, operation, STILL_BLOCKED);
  }

  /** Waits for an operation that was blocked and has been released. */
  static String outcomeOf(final Future<String> pending)
      throws ExecutionException, InterruptedException, TimeoutException {

    return pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Starts both operations behind a barrier, so that they reach the database at the same time, and
   * returns their outcomes in the order given.
   */
  static List<String> runTogether(
      final ExecutorService executor, final Callable<String> first, final Callable<String> second)
      throws ExecutionException, InterruptedException, TimeoutException {

    final var barrier = new CyclicBarrier(2);
    final var one =
        executor.submit(
            () -> {
              barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
              return first.call();
            });
    final var other =
        executor.submit(
            () -> {
              barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
              return second.call();
            });

    return List.of(
        one.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
  }
}
