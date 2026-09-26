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
package io.repsy.protocols.nuget.shared.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyGroupInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("NuGetResponseMapper dependency groups (RPS-1555)")
class NuGetResponseMapperTest {

  private static final String LEAF_URL = "https://repo/v3/registration/some.package/1.0.0.json";

  @Test
  @DisplayName("emits a group without dependencies and leaves its dependencies property out")
  void emitsEmptyGroupWithoutDependencies() throws Exception {
    final var version =
        version(
            null,
            List.of(
                new NuGetDependencyGroupInfo("net10.0", List.of()),
                new NuGetDependencyGroupInfo(
                    ".NETStandard2.0",
                    List.of(
                        new NuGetDependencyInfo("Serilog", "3.1.1", ".NETStandard2.0"),
                        new NuGetDependencyInfo("Newtonsoft.Json", "", ".NETStandard2.0")))));

    final var groups =
        NuGetResponseMapper.toCatalogEntry(version, "Some.Package", LEAF_URL).dependencyGroups();

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).targetFramework()).isEqualTo("net10.0");
    assertThat(groups.get(0).dependencies()).isNull();
    assertThat(groups.get(1).dependencies()).hasSize(2);

    final var json =
        new ObjectMapper()
            .readTree(
                new ObjectMapper()
                    .writeValueAsString(
                        NuGetResponseMapper.toCatalogEntry(version, "Some.Package", LEAF_URL)));
    final var empty = json.path("dependencyGroups").get(0);
    assertThat(empty.path("@type").asString()).isEqualTo("PackageDependencyGroup");
    assertThat(empty.path("targetFramework").asString()).isEqualTo("net10.0");
    assertThat(empty.has("dependencies")).isFalse();
    final var dependencies = json.path("dependencyGroups").get(1).path("dependencies");
    assertThat(dependencies.get(0).path("range").asString()).isEqualTo("3.1.1");
    assertThat(dependencies.get(1).has("range")).isFalse();
  }

  @Test
  @DisplayName("groups the flat dependencies by target framework when no groups are given")
  void groupsFlatDependencies() {
    final var version =
        version(
            List.of(
                new NuGetDependencyInfo("A", "1.0", "net8.0"),
                new NuGetDependencyInfo("B", "", null),
                new NuGetDependencyInfo("C", "2.0", "net8.0")),
            null);

    final var groups =
        NuGetResponseMapper.toCatalogEntry(version, "Some.Package", LEAF_URL).dependencyGroups();

    assertThat(groups).hasSize(2);
    assertThat(groups.get(0).targetFramework()).isEqualTo("net8.0");
    assertThat(groups.get(0).dependencies()).hasSize(2);
    assertThat(groups.get(1).targetFramework()).isNull();
    assertThat(groups.get(1).dependencies()).hasSize(1);
  }

  @Test
  @DisplayName("omits dependencyGroups when the version declares nothing")
  void omitsDependencyGroupsWithoutAny() {
    assertThat(
            NuGetResponseMapper.toCatalogEntry(version(null, null), "Some.Package", LEAF_URL)
                .dependencyGroups())
        .isNull();
    assertThat(
            NuGetResponseMapper.toCatalogEntry(
                    version(List.of(), List.of()), "Some.Package", LEAF_URL)
                .dependencyGroups())
        .isNull();
  }

  private static NuGetVersionInfo version(
      final @Nullable List<NuGetDependencyInfo> dependencies,
      final @Nullable List<NuGetDependencyGroupInfo> groups) {
    return new NuGetVersionInfo(
        "Some.Package",
        "1.0.0",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        0L,
        Instant.parse("2026-01-01T00:00:00Z"),
        dependencies,
        null,
        groups);
  }
}
