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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Public entry to {@link TrivyTestFiles} for tests outside this package. */
public final class TrivyTestFilesAccess {

  private TrivyTestFilesAccess() {}

  /** Both databases, published at 2026-09-26T19:03:57.371914884Z and not due for a refresh. */
  public static void writeDatabases(final Path cacheDir) throws IOException {
    final var updated = Instant.parse("2026-09-26T19:03:57.371914884Z");
    TrivyTestFiles.writeVulnerabilityDb(cacheDir, "db", updated, TrivyTestFiles.FAR_AHEAD);
    TrivyTestFiles.writeJavaDb(cacheDir, "java-db", updated, TrivyTestFiles.FAR_AHEAD);
  }
}
