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
package io.repsy.protocols.nuget.shared.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;

@Slf4j
@NullMarked
@UtilityClass
public final class NuGetPackageUtils {

  private static final int BUFFER = 4096;

  public static String extractNuspecFromNupkg(final InputStream nupkgInputStream)
      throws IOException {
    try (final var zis = new ZipInputStream(new BufferedInputStream(nupkgInputStream))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.getName().endsWith(".nuspec")) {
          return readNuspecContent(zis);
        }
      }
      throw new IOException("No .nuspec file found in nupkg");
    }
  }

  private static String readNuspecContent(final ZipInputStream zis) throws IOException {
    final var sb = new StringBuilder();
    final var buffer = new byte[BUFFER];
    int bytesRead;

    while ((bytesRead = zis.read(buffer)) != -1) {
      sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
    }

    return sb.toString();
  }

  //  public static byte[] extractBytesFromNupkg(final InputStream nupkgInputStream)
  //      throws IOException {
  //    final var buffer = new byte[8192];
  //    final var result = new java.io.ByteArrayOutputStream();
  //    int bytesRead;
  //
  //    try (final var buffered = new BufferedInputStream(nupkgInputStream)) {
  //      while ((bytesRead = buffered.read(buffer)) != -1) {
  //        result.write(buffer, 0, bytesRead);
  //      }
  //    }
  //
  //    return result.toByteArray();
  //  }
}
