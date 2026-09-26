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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Coordinates the trivy runs that open the vulnerability database in one cache directory.
 *
 * <p>Trivy does not do it (measured with Trivy 0.66.0): a {@code rootfs} or {@code image} scan
 * keeps the database open for its whole run, and another trivy process that opens it meanwhile
 * fails after one second with "vulnerability database may be in use by another process". A download
 * deletes {@code metadata.json}, then writes {@code trivy.db} in place, and writes the metadata
 * again only at the end, so a lookup that starts in between sees a partial file and fails with
 * "--skip-db-update cannot be specified on the first run". Two short lookups can overlap, as
 * measured.
 *
 * <p>So the runs are grouped by kind:
 *
 * <ul>
 *   <li>a scan ({@link #enterScan()}) may overlap other scans, as before, but not a lookup or a
 *       refresh;
 *   <li>a lookup ({@link #tryEnterLookup(Duration)}) may overlap other lookups, but not a scan or a
 *       refresh; it gives up after a bounded wait instead of queueing behind a long scan;
 *   <li>a refresh ({@link #enterRefresh()}) runs alone.
 * </ul>
 *
 * A scan or a refresh that is waiting holds back new lookups, and a waiting refresh holds back new
 * scans, so neither can be starved by a stream of the others.
 */
@Component
public class TrivyDatabaseAccess {

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = this.lock.newCondition();

  private int activeScans;
  private int waitingScans;
  private int activeLookups;
  private int waitingRefreshes;
  private boolean refreshActive;

  /** Held while a trivy run uses the database; close it when the run is over. */
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  /** Waits for the database (lookups, a refresh) and returns the permit of a scan. */
  public @NonNull Permit enterScan() {
    this.lock.lock();
    try {
      this.waitingScans++;
      try {
        while (this.scanMustWait()) {
          this.changed.await();
        }
      } finally {
        this.waitingScans--;
      }

      this.activeScans++;
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      this.changed.signalAll();
      throw new TrivyScanException("Interrupted while waiting for the vulnerability database");
    } finally {
      this.lock.unlock();
    }

    return this.permit(() -> this.activeScans--);
  }

  /** Waits for everything else to finish and returns the permit of a refresh. */
  public @NonNull Permit enterRefresh() {
    this.lock.lock();
    try {
      this.waitingRefreshes++;
      try {
        while (this.refreshMustWait()) {
          this.changed.await();
        }
      } finally {
        this.waitingRefreshes--;
      }

      this.refreshActive = true;
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      this.changed.signalAll();
      throw new TrivyScanException("Interrupted while waiting for the vulnerability database");
    } finally {
      this.lock.unlock();
    }

    return this.permit(() -> this.refreshActive = false);
  }

  /** The permit of a lookup, or empty when a scan or a refresh keeps the database for too long. */
  public @NonNull Optional<Permit> tryEnterLookup(final @NonNull Duration maxWait) {
    var remainingNanos = maxWait.toNanos();

    this.lock.lock();
    try {
      while (this.lookupMustWait()) {
        if (remainingNanos <= 0) {
          return Optional.empty();
        }

        remainingNanos = this.changed.awaitNanos(remainingNanos);
      }

      this.activeLookups++;
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      this.lock.unlock();
    }

    return Optional.of(this.permit(() -> this.activeLookups--));
  }

  private boolean scanMustWait() {
    return this.activeLookups > 0 || this.refreshActive || this.waitingRefreshes > 0;
  }

  private boolean refreshMustWait() {
    return this.activeScans > 0 || this.activeLookups > 0 || this.refreshActive;
  }

  private boolean lookupMustWait() {
    return this.activeScans > 0
        || this.waitingScans > 0
        || this.refreshActive
        || this.waitingRefreshes > 0;
  }

  private @NonNull Permit permit(final @NonNull Runnable release) {
    return new Permit() {
      private boolean closed;

      @Override
      public void close() {
        TrivyDatabaseAccess.this.lock.lock();
        try {
          if (this.closed) {
            return;
          }

          this.closed = true;
          release.run();
          TrivyDatabaseAccess.this.changed.signalAll();
        } finally {
          TrivyDatabaseAccess.this.lock.unlock();
        }
      }
    };
  }
}
