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
package io.repsy.protocols.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

@DisplayName("StoredUpload")
class StoredUploadTest {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(StoredUploadTest.class);

  @Test
  @DisplayName("returns the write's result and discards nothing when it succeeds")
  void returnsResultWithoutDiscarding() throws Exception {
    final var discarded = new AtomicInteger();

    final var result =
        StoredUpload.storeOrDiscard(() -> 42, discarded::incrementAndGet, false, LOG, "x");

    assertThat(result).isEqualTo(42);
    assertThat(discarded).hasValue(0);
  }

  @Test
  @DisplayName("discards and rethrows the same checked failure")
  void discardsOnCheckedFailure() {
    final var discarded = new AtomicInteger();
    final var failure = new IOException("disk full");

    assertThatThrownBy(
            () ->
                StoredUpload.storeOrDiscard(
                    () -> {
                      throw failure;
                    },
                    discarded::incrementAndGet,
                    false,
                    LOG,
                    "x"))
        .isSameAs(failure);

    assertThat(discarded).hasValue(1);
  }

  @Test
  @DisplayName("discards and rethrows the same unchecked failure")
  void discardsOnUncheckedFailure() {
    final var discarded = new AtomicInteger();
    final var failure = new IllegalStateException("boom");

    assertThatThrownBy(
            () ->
                StoredUpload.runOrDiscard(
                    () -> {
                      throw failure;
                    },
                    discarded::incrementAndGet,
                    false,
                    LOG,
                    Level.WARN,
                    "x"))
        .isSameAs(failure);

    assertThat(discarded).hasValue(1);
  }

  @Test
  @DisplayName("keeps the files when the upload replaces an existing one")
  void keepsFilesWhenReplacing() {
    final var discarded = new AtomicInteger();
    final var failure = new IOException("disk full");

    assertThatThrownBy(
            () ->
                StoredUpload.storeOrDiscard(
                    () -> {
                      throw failure;
                    },
                    discarded::incrementAndGet,
                    true,
                    LOG,
                    "x"))
        .isSameAs(failure);

    assertThat(discarded).hasValue(0);
  }

  @Test
  @DisplayName("a failed discard never replaces the write's failure; it is suppressed on it")
  void failedDiscardIsSuppressed() {
    final var failure = new IOException("disk full");
    final var discardFailure = new IllegalStateException("nothing there");

    assertThatThrownBy(
            () ->
                StoredUpload.storeOrDiscard(
                    () -> {
                      throw failure;
                    },
                    () -> {
                      throw discardFailure;
                    },
                    false,
                    LOG,
                    "x"))
        .isSameAs(failure)
        .hasSuppressedException(discardFailure);
  }
}
