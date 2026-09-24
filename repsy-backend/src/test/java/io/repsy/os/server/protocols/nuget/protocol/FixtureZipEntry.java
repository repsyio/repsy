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
package io.repsy.os.server.protocols.nuget.protocol;

import java.time.LocalDateTime;
import java.util.zip.ZipEntry;

/**
 * Builds the zip entries of the {@code .nupkg} fixtures with a constant timestamp (RPS-1281).
 *
 * <p>{@link ZipEntry} stamps every entry with the wall clock at DOS granularity (2 seconds), so two
 * builds of the same fixture that straddle a 2-second boundary differ in the local-header and
 * central-directory time bytes. A test that pushes a fixture and then compares the stored bytes
 * with a freshly built copy of it would then fail at random. Pinning the time makes a fixture a
 * pure function of its content.
 */
final class FixtureZipEntry {

  /** Any instant the DOS format can hold (1980 to 2107); the value itself does not matter. */
  private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

  private FixtureZipEntry() {}

  static ZipEntry named(final String name) {
    final var entry = new ZipEntry(name);
    entry.setTimeLocal(FIXED_TIME);

    return entry;
  }
}
