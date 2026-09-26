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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TrivyDatabaseAccessTest {

  private static final Duration NO_WAIT = Duration.ZERO;

  private final TrivyDatabaseAccess access = new TrivyDatabaseAccess();

  @Test
  void lookupsShareTheDatabase() {
    try (var first = this.access.tryEnterLookup(NO_WAIT).orElseThrow();
        var second = this.access.tryEnterLookup(NO_WAIT).orElseThrow()) {
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
    }
  }

  @Test
  void scansShareTheDatabase() {
    try (var first = this.access.enterScan();
        var second = this.access.enterScan()) {
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
    }
  }

  @Test
  void aLookupIsRefusedWhileAScanRunsAndAllowedAfterwards() {
    final var scan = this.access.enterScan();

    assertThat(this.access.tryEnterLookup(NO_WAIT)).isEmpty();

    scan.close();

    assertThat(this.access.tryEnterLookup(NO_WAIT)).isPresent();
  }

  @Test
  void aLookupWaitsForAShortScanWithinItsMaxWait() throws Exception {
    final var scan = this.access.enterScan();

    final var lookup =
        CompletableFuture.supplyAsync(() -> this.access.tryEnterLookup(Duration.ofSeconds(10)));
    Thread.sleep(100);
    scan.close();

    assertThat(lookup.get(5, TimeUnit.SECONDS)).isPresent();
  }

  @Test
  void aScanWaitsForALookupToFinish() throws Exception {
    final var lookup = this.access.tryEnterLookup(NO_WAIT).orElseThrow();

    final var scan = CompletableFuture.supplyAsync(this.access::enterScan);
    Thread.sleep(100);
    assertThat(scan).isNotDone();

    lookup.close();

    assertThat(scan.get(5, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void aWaitingScanHoldsBackNewLookupsSoAStreamOfLookupsCannotStarveIt() throws Exception {
    final var lookup = this.access.tryEnterLookup(NO_WAIT).orElseThrow();
    final var scan = CompletableFuture.supplyAsync(this.access::enterScan);
    Thread.sleep(100);

    assertThat(this.access.tryEnterLookup(NO_WAIT)).isEmpty();

    lookup.close();
    scan.get(5, TimeUnit.SECONDS).close();
  }

  @Test
  void aRefreshWaitsForScansAndLookupsAndRunsAlone() throws Exception {
    final var scan = this.access.enterScan();
    final var lookupWhileScanning = this.access.tryEnterLookup(NO_WAIT);
    assertThat(lookupWhileScanning).isEmpty();

    final var refresh = CompletableFuture.supplyAsync(this.access::enterRefresh);
    Thread.sleep(100);
    assertThat(refresh).isNotDone();
    // a waiting refresh holds back what would come next
    assertThat(this.access.tryEnterLookup(NO_WAIT)).isEmpty();

    scan.close();
    final var refreshPermit = refresh.get(5, TimeUnit.SECONDS);

    assertThat(this.access.tryEnterLookup(NO_WAIT)).isEmpty();
    final var scanAfter = CompletableFuture.supplyAsync(this.access::enterScan);
    Thread.sleep(100);
    assertThat(scanAfter).isNotDone();

    refreshPermit.close();

    scanAfter.get(5, TimeUnit.SECONDS).close();
    assertThat(this.access.tryEnterLookup(NO_WAIT)).isPresent();
  }

  @Test
  void closingAPermitTwiceReleasesItOnce() {
    final var first = this.access.enterScan();
    final var second = this.access.enterScan();

    first.close();
    first.close();

    // the second scan still runs: a double close must not have released it
    assertThat(this.access.tryEnterLookup(NO_WAIT)).isEmpty();

    second.close();

    assertThat(this.access.tryEnterLookup(NO_WAIT)).isPresent();
  }
}
