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

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyGroupInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class NuGetDependencyJsonUtilsTest {

  private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
  private final Logger utilsLogger =
      (Logger) LoggerFactory.getLogger(NuGetDependencyJsonUtils.class);

  @BeforeEach
  void captureLogs() {
    this.logEvents.start();
    this.utilsLogger.addAppender(this.logEvents);
  }

  @AfterEach
  void releaseLogs() {
    this.utilsLogger.detachAppender(this.logEvents);
    this.logEvents.stop();
  }

  private List<String> warnings() {
    return this.logEvents.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Nested
  @DisplayName("stored dependencies (RPS-1015)")
  class StoredDependencies {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "<ArrayList><item><packageId>Legacy</packageId></item></ArrayList>",
          "[{\"packageId\": \"Cut.Off\"",
          "{\"packageId\": \"Not.An.Array\"}",
          "not json"
        })
    @DisplayName(
        "warns with the package id and version, not the value, and returns no dependencies")
    void warnsForUnreadableValue(final String stored) {
      assertThat(NuGetDependencyJsonUtils.parseDependenciesJson(stored, "Some.Package", "1.2.3"))
          .isEmpty();

      assertThat(warnings())
          .singleElement()
          .asString()
          .contains("Some.Package", "1.2.3")
          .doesNotContain(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "null"})
    @DisplayName("treats a missing, blank or JSON null value as no dependencies without a warning")
    void treatsAbsentValueAsNoDependencies(final String stored) {
      assertThat(NuGetDependencyJsonUtils.parseDependenciesJson(stored, "Some.Package", "1.2.3"))
          .isEmpty();
      assertThat(NuGetDependencyJsonUtils.parseDependenciesJson(null, "Some.Package", "1.2.3"))
          .isEmpty();

      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("reads stored dependencies without a warning")
    void readsValidValue() {
      final var dependencies = List.of(new NuGetDependencyInfo("Serilog", "3.1.1", "net8.0"));

      assertThat(
              NuGetDependencyJsonUtils.parseDependenciesJson(
                  NuGetDependencyJsonUtils.toDependenciesJson(dependencies),
                  "Some.Package",
                  "1.2.3"))
          .isEqualTo(dependencies);

      assertThat(warnings()).isEmpty();
    }
  }

  @Nested
  @DisplayName("stored dependency groups (RPS-1555)")
  class StoredDependencyGroups {

    private final List<NuGetDependencyGroupInfo> groups =
        List.of(
            new NuGetDependencyGroupInfo("net10.0", List.of()),
            new NuGetDependencyGroupInfo(
                "net8.0", List.of(new NuGetDependencyInfo("Serilog", "3.1.1", "net8.0"))),
            new NuGetDependencyGroupInfo(
                null, List.of(new NuGetDependencyInfo("Newtonsoft.Json", "", null))));

    @Test
    @DisplayName("keeps a group without dependencies through a store and read round trip")
    void roundTripKeepsEmptyGroup() {
      final var json = NuGetDependencyJsonUtils.toDependencyGroupsJson(groups);

      assertThat(json).startsWith("{\"groups\":");
      assertThat(NuGetDependencyJsonUtils.parseDependencyGroupsJson(json, "Some.Package", "1.2.3"))
          .isEqualTo(groups);
      assertThat(NuGetDependencyJsonUtils.parseDependenciesJson(json, "Some.Package", "1.2.3"))
          .containsExactly(
              new NuGetDependencyInfo("Serilog", "3.1.1", "net8.0"),
              new NuGetDependencyInfo("Newtonsoft.Json", "", null));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("stores the flat list of earlier versions when no group is empty")
    void storesFlatListWithoutEmptyGroup() {
      final var full = groups.subList(1, 3);

      assertThat(NuGetDependencyJsonUtils.toDependencyGroupsJson(full))
          .isEqualTo(
              NuGetDependencyJsonUtils.toDependenciesJson(NuGetDependencyJsonUtils.flatten(full)))
          .startsWith("[");
      assertThat(
              NuGetDependencyJsonUtils.parseDependencyGroupsJson(
                  NuGetDependencyJsonUtils.toDependencyGroupsJson(full), "Some.Package", "1.2.3"))
          .isEqualTo(full);
    }

    @Test
    @DisplayName("reads the flat list of earlier versions as groups, in order of first appearance")
    void readsLegacyFlatListAsGroups() {
      final var legacy =
          """
          [{"packageId":"A","versionRange":"1.0","targetFramework":"net8.0"},
          {"packageId":"B","versionRange":"","targetFramework":null},
          {"packageId":"C","versionRange":"2.0","targetFramework":"net8.0"}]
          """;

      assertThat(
              NuGetDependencyJsonUtils.parseDependencyGroupsJson(legacy, "Some.Package", "1.2.3"))
          .containsExactly(
              new NuGetDependencyGroupInfo(
                  "net8.0",
                  List.of(
                      new NuGetDependencyInfo("A", "1.0", "net8.0"),
                      new NuGetDependencyInfo("C", "2.0", "net8.0"))),
              new NuGetDependencyGroupInfo(null, List.of(new NuGetDependencyInfo("B", "", null))));
      assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("warns for a grouped value whose groups are not a list")
    void warnsForGroupsThatAreNotAList() {
      assertThat(
              NuGetDependencyJsonUtils.parseDependencyGroupsJson(
                  "{\"groups\":\"x\"}", "Some.Package", "1.2.3"))
          .isEmpty();
      assertThat(warnings()).singleElement().asString().contains("Some.Package", "1.2.3");
    }
  }
}
