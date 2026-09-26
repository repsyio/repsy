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
package io.repsy.scanner.trivy.services;

import io.repsy.scanner.trivy.errors.TrivyScanException;
import io.repsy.scanner.trivy.errors.TrivyTimeoutException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProcessTrivyCommandRunner implements TrivyCommandRunner {

  private static final int MAX_ERROR_MESSAGE_LENGTH = 1900;
  private static final String TRUNCATION_SUFFIX = "... [truncated]";

  @Override
  public @NonNull String run(final @NonNull List<String> command, final long timeoutSeconds) {
    final Process process;

    try {
      process = new ProcessBuilder(command).start();
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to start trivy process", exception);
    }

    final var stdoutFuture =
        CompletableFuture.supplyAsync(() -> readFully(process.getInputStream()));
    final var stderrFuture =
        CompletableFuture.supplyAsync(() -> readFully(process.getErrorStream()));

    final var finished = waitForProcess(process, timeoutSeconds);

    if (!finished) {
      // Kill before joining the readers: they only end when the process closes its streams.
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }

    final var stdout = stdoutFuture.join();
    final var stderr = stderrFuture.join();

    if (StringUtils.isNotBlank(stderr)) {
      log.debug("trivy stderr output: {}", stderr);
    }

    if (!finished) {
      throw new TrivyTimeoutException("Trivy scan timed out after " + timeoutSeconds + "s");
    }

    if (process.exitValue() != 0) {
      throw new TrivyScanException(
          truncate("trivy exited with code " + process.exitValue() + ": " + stderr));
    }

    return stdout;
  }

  private static boolean waitForProcess(final @NonNull Process process, final long timeoutSeconds) {
    try {
      return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new TrivyScanException("Interrupted while waiting for trivy", exception);
    }
  }

  private static @NonNull String readFully(final @NonNull InputStream inputStream) {
    try {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      throw new TrivyScanException("Failed to read trivy process output", exception);
    }
  }

  private static @NonNull String truncate(final @NonNull String message) {
    if (message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
      return message;
    }

    return message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + TRUNCATION_SUFFIX;
  }
}
