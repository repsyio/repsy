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
package io.repsy.protocols.shared.repo.dtos;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

public enum RepoType {
  MAVEN("mvn__"),
  NPM("npm__"),
  PYPI("pypi__"),
  DOCKER("docker__"),
  CARGO("cargo__"),
  GOLANG("go__"),
  HELM("helm__"),
  NUGET("nuget__"),
  RUBY("ruby__");

  private final @NonNull String prefix;

  RepoType(final @NonNull String prefix) {
    this.prefix = prefix;
  }

  public @NonNull String getPrefix() {
    return this.prefix;
  }

  public @NonNull String withPrefix(final @NonNull String repoName) {
    return this.prefix + repoName;
  }

  /**
   * Finds the type named by {@code value}, without regard to case ({@code maven} is {@code MAVEN})
   * and ignoring surrounding whitespace. The canonical spelling everywhere is the upper-case {@link
   * #name()}; this is the one place that accepts the lower-case slug too.
   */
  public static Optional<RepoType> fromString(final String value) {
    if (value == null) {
      return Optional.empty();
    }

    final var normalized = value.strip().toUpperCase(Locale.ROOT);

    return Arrays.stream(values()).filter(t -> t.name().equals(normalized)).findFirst();
  }

  /**
   * The JSON reader of this enum, case-insensitive. It exists so the panel API accepts {@code
   * "maven"} as well as {@code "MAVEN"} in a request body; it is scoped to this enum only, other
   * enums (severity, role...) keep Jackson's exact-name matching.
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static RepoType fromJson(final String value) {
    return fromString(value)
        .orElseThrow(() -> new IllegalArgumentException("Unknown repo type: " + value));
  }
}
