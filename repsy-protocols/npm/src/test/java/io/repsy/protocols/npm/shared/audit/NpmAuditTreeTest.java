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
package io.repsy.protocols.npm.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.protocols.npm.shared.audit.NpmAuditTree.Node;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("NpmAuditTree")
class NpmAuditTreeTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A mapper that reads a tree as deep as the test builds one. */
  private static JsonMapper unlimitedMapper() {
    return JsonMapper.builder(
            JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxNestingDepth(100_000).build())
                .build())
        .build();
  }

  private static JsonNode json(final String text) {
    return MAPPER.readTree(text);
  }

  private static List<String> paths(final NpmAuditTree tree) {
    return tree.nodes().stream().map(Node::path).toList();
  }

  @Test
  @DisplayName("walks an npm 6 tree, listing the packages of a level before their dependencies")
  void npmSixTree() {
    final var tree =
        NpmAuditTree.parse(
            json(
                """
                {"name":"app","version":"1.0.0","requires":{"a":"^1.0.0"},
                 "dependencies":{
                   "a":{"version":"1.0.0","requires":{"lodash":"^4.0.0"},
                        "dependencies":{"lodash":{"version":"4.17.20"}}},
                   "b":{"version":"2.0.0","dev":true},
                   "c":{"version":"3.0.0","optional":true,"bundled":true},
                   "lodash":{"version":"4.17.21"}
                 },
                 "install":[],"remove":[],"metadata":{}}
                """));

    assertThat(paths(tree)).containsExactly("a", "b", "c", "lodash", "a>lodash");
    assertThat(tree.versionsByName().get("lodash")).containsExactlyInAnyOrder("4.17.20", "4.17.21");
    assertThat(tree.nodes().get(1).dev()).isTrue();
    assertThat(tree.nodes().get(2).optional()).isTrue();
    assertThat(tree.nodes().get(2).bundled()).isTrue();
    assertThat(tree.nodes().get(0).dev()).isFalse();
  }

  @Test
  @DisplayName("counts dependencies, dev dependencies, optional dependencies and all of them")
  void counts() {
    final var tree =
        NpmAuditTree.parse(
            json(
                """
                {"dependencies":{
                   "a":{"version":"1.0.0","dependencies":{"b":{"version":"1.0.0","dev":true}}},
                   "c":{"version":"1.0.0","dev":true},
                   "d":{"version":"1.0.0","optional":true}}}
                """));

    assertThat(tree.dependencies()).isEqualTo(2);
    assertThat(tree.devDependencies()).isEqualTo(2);
    assertThat(tree.optionalDependencies()).isEqualTo(1);
    assertThat(tree.totalDependencies()).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "takes the importers of a pnpm tree as ordinary nodes, counting only those with a version")
  void pnpmTree() {
    final var tree =
        NpmAuditTree.parse(
            json(
                """
                {"name":"root","version":"undefined","dependencies":{
                   ".":{"dependencies":{"a":{"version":"1.0.0","dependencies":{"b":{"version":"2.0.0"}}}}},
                   "packages__x":{"version":"undefined","dependencies":{"a":{"version":"1.0.0"}}}},
                 "dev":false,"install":[],"remove":[],"metadata":{},"requires":{}}
                """));

    assertThat(paths(tree))
        .containsExactlyInAnyOrder("packages__x", ".>a", "packages__x>a", ".>a>b");
    assertThat(tree.versionsByName().get("a")).containsExactly("1.0.0");
  }

  @Test
  @DisplayName("skips what is not a package: no version, no object, no dependencies")
  void skipsJunk() {
    final var tree =
        NpmAuditTree.parse(
            json(
                """
                {"dependencies":{"a":"1.0.0","b":{"version":7},"c":{"version":"1.0.0","dependencies":"x"},
                                 "d":{"dependencies":{"e":{"version":"2.0.0"}}}}}
                """));

    assertThat(paths(tree)).containsExactly("c", "d>e");
  }

  @Test
  @DisplayName("an empty request, or one without dependencies, is an empty tree")
  void emptyTree() {
    assertThat(NpmAuditTree.parse(json("{}")).nodes()).isEmpty();
    assertThat(NpmAuditTree.parse(json("{\"dependencies\":[]}")).versionsByName()).isEmpty();
  }

  @Test
  @DisplayName("refuses a tree that is nested deeper than the limit")
  void refusesADeepTree() {
    final var depth = NpmAuditTree.MAX_DEPTH + 1;
    final var text = new StringBuilder("{\"dependencies\":");
    for (var i = 0; i < depth; i++) {
      text.append("{\"p\":{\"version\":\"1.0.0\",\"dependencies\":");
    }
    text.append("{}");
    text.append("}}".repeat(depth));
    text.append('}');

    // Parsed with a mapper without the nesting limit, which is what the real limit is checked for.
    final var unlimited = unlimitedMapper();

    assertThatThrownBy(() -> NpmAuditTree.parse(unlimited.readTree(text.toString())))
        .isInstanceOf(InvalidAuditRequestException.class)
        .hasMessageContaining("deeply");
  }

  @Test
  @DisplayName("accepts a tree right at the depth limit")
  void acceptsTheLimit() {
    final var depth = NpmAuditTree.MAX_DEPTH - 1;
    final var text = new StringBuilder("{\"dependencies\":");
    for (var i = 0; i < depth; i++) {
      text.append("{\"p\":{\"version\":\"1.0.0\",\"dependencies\":");
    }
    text.append("{}");
    text.append("}}".repeat(depth));
    text.append('}');
    final var unlimited = unlimitedMapper();

    final var tree = NpmAuditTree.parse(unlimited.readTree(text.toString()));

    assertThat(tree.totalDependencies()).isEqualTo(depth);
  }
}
