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
package io.repsy.protocols.golang.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** Reads the {@code go.mod} out of an uploaded module zip. */
@UtilityClass
@NullMarked
public class GoModuleZipReader {

  /**
   * The largest {@code go.mod} a module zip may carry, in bytes. A real {@code go.mod} is a few
   * kilobytes. The limit is the one the {@code go} command itself enforces on a module zip ({@code
   * MaxGoMod} in golang.org/x/mod/zip), so no zip the toolchain would build or accept is refused.
   * It only has to stop a decompression bomb: the read is bounded, so no more than this is ever
   * inflated into memory.
   */
  public static final long MAX_GO_MOD_BYTES = 16L * 1024 * 1024;

  /**
   * Returns the content of {@code <modulePath>@<version>/go.mod} in {@code zipContent}, which is
   * closed once the entry is found (or the zip is exhausted).
   *
   * @throws BadRequestException {@code goModNotFoundInZip} if the zip has no such entry, {@code
   *     goModTooLarge} if it inflates past {@link #MAX_GO_MOD_BYTES}
   */
  @SneakyThrows
  public static byte[] extractGoMod(
      final InputStream zipContent, final String modulePath, final String version) {

    final var entryName = modulePath + "@" + version + "/go.mod";

    try (final var zis = new ZipInputStream(zipContent)) {
      var entry = zis.getNextEntry();

      while (entry != null) {
        if (entryName.equals(entry.getName())) {
          // A zip entry does not always carry its size in the header (a data descriptor leaves it
          // unknown), so BoundedEntryReader bounds the read itself and uses the header only as a
          // shortcut.
          return BoundedEntryReader.readAllBytes(zis, entry.getSize(), MAX_GO_MOD_BYTES);
        }

        zis.closeEntry();
        entry = zis.getNextEntry();
      }
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("goModTooLarge");
    }

    throw new BadRequestException("goModNotFoundInZip");
  }
}
