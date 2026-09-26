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

import com.fasterxml.jackson.annotation.JsonProperty;
import io.repsy.scanner.trivy.dtos.AdvisoryPackage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns (name, version) pairs into a CycloneDX 1.5 file that {@code trivy sbom} reads: one library
 * component per pair, identified by its package URL. The format does not depend on the ecosystem;
 * npm is the only one that {@link #npmPurl} builds for now.
 */
@Component
@RequiredArgsConstructor
public class CycloneDxSbomWriter {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  private static final String UNRESERVED_MARKS = "-._~";

  private final @NonNull ObjectMapper objectMapper;

  public void writeNpm(final @NonNull Path file, final @NonNull List<AdvisoryPackage> packages)
      throws IOException {

    try {
      this.objectMapper.writeValue(file.toFile(), buildNpm(packages));
    } catch (final JacksonException exception) {
      throw new IOException("Failed to write the SBOM", exception);
    }
  }

  static @NonNull Bom buildNpm(final @NonNull List<AdvisoryPackage> packages) {
    final var components = new ArrayList<BomComponent>(packages.size());

    for (var index = 0; index < packages.size(); index++) {
      final var pair = packages.get(index);
      components.add(
          new BomComponent(
              "library",
              "c" + index,
              pair.name(),
              pair.version(),
              npmPurl(pair.name(), pair.version())));
    }

    return new Bom("CycloneDX", "1.5", 1, components);
  }

  /**
   * {@code pkg:npm/<namespace>/<name>@<version>} per the package-url specification: the scope of a
   * scoped package is the namespace and keeps its "@" as {@code %40}; every character that is not
   * an unreserved one is percent-encoded (UTF-8), so a "+" of a build tag or a "/" cannot change
   * the meaning of the URL.
   */
  static @NonNull String npmPurl(final @NonNull String name, final @NonNull String version) {
    final var slash = name.indexOf('/');
    final var purl = new StringBuilder("pkg:npm/");

    if (slash >= 0) {
      purl.append(encode(name.substring(0, slash)))
          .append('/')
          .append(encode(name.substring(slash + 1)));
    } else {
      purl.append(encode(name));
    }

    return purl.append('@').append(encode(version)).toString();
  }

  private static @NonNull String encode(final @NonNull String value) {
    final var encoded = new StringBuilder(value.length());

    for (final var octet : value.getBytes(StandardCharsets.UTF_8)) {
      if (isUnreserved(octet)) {
        encoded.append((char) octet);
      } else {
        encoded.append('%').append(HEX.toHighHexDigit(octet)).append(HEX.toLowHexDigit(octet));
      }
    }

    return encoded.toString();
  }

  // ASCII letters and digits and "-._~"; a byte of a multi-byte UTF-8 character is negative here.
  private static boolean isUnreserved(final byte octet) {
    return octet >= 0 && (Character.isLetterOrDigit(octet) || UNRESERVED_MARKS.indexOf(octet) >= 0);
  }

  record Bom(
      @NonNull String bomFormat,
      @NonNull String specVersion,
      int version,
      @NonNull List<BomComponent> components) {}

  record BomComponent(
      @NonNull String type,
      @JsonProperty("bom-ref") @NonNull String bomRef,
      @NonNull String name,
      @NonNull String version,
      @NonNull String purl) {}
}
