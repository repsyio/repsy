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
package io.repsy.protocols.docker.protocol.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RPS-1434: what a token from {@code /v2/token} records and what it allows. */
@DisplayName("DockerScopes")
class DockerScopesTest {

  @Test
  @DisplayName("parseGrants keeps the repository scopes with lower-case names and actions")
  void parsesRepositoryScopes() {
    assertThat(DockerScopes.parseGrants(new String[] {"repository:Repo/App:Pull,PUSH"}))
        .containsExactly("repo/app:pull,push");
  }

  @Test
  @DisplayName("parseGrants reads several scopes in one parameter and several parameters")
  void parsesSeveralScopes() {
    assertThat(
            DockerScopes.parseGrants(
                new String[] {
                  "repository:repo/app:pull  repository:repo/lib:push,pull",
                  "repository:repo/app:delete"
                }))
        .containsExactly("repo/app:pull", "repo/lib:push,pull", "repo/app:delete");
  }

  @Test
  @DisplayName("parseGrants drops duplicate scopes and duplicate actions")
  void dropsDuplicates() {
    assertThat(
            DockerScopes.parseGrants(
                new String[] {"repository:r/a:pull,pull,push", "repository:r/a:pull,push"}))
        .containsExactly("r/a:pull,push");
  }

  @Test
  @DisplayName("parseGrants ignores what names no repository or no action")
  void ignoresUnusableScopes() {
    assertThat(
            DockerScopes.parseGrants(
                new String[] {
                  "",
                  "registry:catalog:*",
                  "repository:",
                  "repository:r/a",
                  "repository:r/a:",
                  "repository::pull",
                  "garbage"
                }))
        .isEmpty();
  }

  @Test
  @DisplayName("parseGrants of no parameter is an empty list")
  void noParameter() {
    assertThat(DockerScopes.parseGrants(null)).isEmpty();
    assertThat(DockerScopes.parseGrants(new String[0])).isEmpty();
  }

  @Test
  @DisplayName("parseGrants keeps a colon that belongs to the name")
  void nameWithColon() {
    assertThat(DockerScopes.parseGrants(new String[] {"repository:host:5000/r/a:delete"}))
        .containsExactly("host:5000/r/a:delete");
  }

  @Test
  @DisplayName("allowsDelete needs the delete action or * on exactly that name")
  void allowsDelete() {
    assertThat(DockerScopes.allowsDelete(List.of("r/a:delete"), "r/a")).isTrue();
    assertThat(DockerScopes.allowsDelete(List.of("r/a:push,pull,delete"), "r/a")).isTrue();
    assertThat(DockerScopes.allowsDelete(List.of("r/a:*"), "r/a")).isTrue();
    assertThat(DockerScopes.allowsDelete(List.of("r/b:pull", "r/a:delete"), "r/a")).isTrue();
    assertThat(DockerScopes.allowsDelete(List.of("r/a:delete"), "R/A")).isTrue();
  }

  @Test
  @DisplayName("allowsDelete refuses pull, push, another image, another repo and a wildcard name")
  void refusesDelete() {
    assertThat(DockerScopes.allowsDelete(List.of(), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("r/a:pull"), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("r/a:push,pull"), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("r/b:delete"), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("s/a:delete"), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("*:delete"), "r/a")).isFalse();
    assertThat(DockerScopes.allowsDelete(List.of("no-actions"), "r/a")).isFalse();
  }
}
