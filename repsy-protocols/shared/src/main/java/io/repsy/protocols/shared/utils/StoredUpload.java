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

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The "store, and when that fails remove what was half written" step of a publish (RPS-1124).
 *
 * <p>A facade writes its files while the service still holds the version's rows. A failure rolls
 * the rows back, so a file left behind would be one no row describes. Every format used to
 * hand-write that step; this is the one copy.
 */
public final class StoredUpload {

  /** A write that may fail with {@code E}. */
  @FunctionalInterface
  public interface Write<T extends @Nullable Object, E extends Exception> {
    T run() throws E;
  }

  /** The removal of whatever a failed write left in storage. */
  @FunctionalInterface
  public interface Discard {
    void run() throws IOException;
  }

  /** A write with no result. */
  @FunctionalInterface
  public interface Step<E extends Exception> {
    void run() throws E;
  }

  private StoredUpload() {}

  /** Same as {@link #storeOrDiscard} for a write with no result. */
  public static <E extends Exception> void runOrDiscard(
      final Step<E> write,
      final Discard discard,
      final boolean keepOnFailure,
      final Logger log,
      final Level level,
      final String what)
      throws E {

    storeOrDiscard(
        () -> {
          write.run();
          return null;
        },
        discard,
        keepOnFailure,
        log,
        level,
        what);
  }

  /** Same as the full overload, logging a failed removal at debug. */
  public static <T extends @Nullable Object, E extends Exception> T storeOrDiscard(
      final Write<T, E> write,
      final Discard discard,
      final boolean keepOnFailure,
      final Logger log,
      final String what)
      throws E {

    return storeOrDiscard(write, discard, keepOnFailure, log, Level.DEBUG, what);
  }

  /**
   * Runs {@code write}; when it fails, runs {@code discard} (unless {@code keepOnFailure}) and
   * rethrows the write's own failure.
   *
   * <p>A failed removal never replaces the write's failure: it is logged and added to it as a
   * suppressed exception. Removal can fail only because there was nothing to remove (the write
   * failed before it created a file), so a caller picks {@link Level#DEBUG} for a removal that is
   * tolerant of that and {@link Level#WARN} where a failure is worth a line in the log.
   *
   * @param write The writes of the upload
   * @param discard Removes what {@code write} may have left behind
   * @param keepOnFailure True when the upload replaces an existing file, whose row survives the
   *     rollback, so its file must stay
   * @param log The caller's logger
   * @param level The level of the line logged when the removal fails
   * @param what What was being stored, for the log line
   */
  public static <T extends @Nullable Object, E extends Exception> T storeOrDiscard(
      final Write<T, E> write,
      final Discard discard,
      final boolean keepOnFailure,
      final Logger log,
      final Level level,
      final String what)
      throws E {

    try {
      return write.run();
    } catch (final Exception e) {
      if (!keepOnFailure) {
        discardQuietly(discard, log, level, what, e);
      }
      throw e;
    }
  }

  private static void discardQuietly(
      final Discard discard,
      final Logger log,
      final Level level,
      final String what,
      final Exception cause) {

    try {
      discard.run();
    } catch (final IOException | RuntimeException e) {
      log.atLevel(level).log("No partial files removed for {}: {}", what, e.getMessage());
      cause.addSuppressed(e);
    }
  }
}
