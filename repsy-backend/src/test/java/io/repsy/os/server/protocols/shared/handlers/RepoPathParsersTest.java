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
package io.repsy.os.server.protocols.shared.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.exceptions.InvalidStoragePathException;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.cargo.protocol.handlers.CargoPathParser;
import io.repsy.os.server.protocols.docker.protocol.handlers.DockerPathParser;
import io.repsy.os.server.protocols.golang.protocol.handlers.GoPathParser;
import io.repsy.os.server.protocols.helm.protocol.utils.HelmChartMuseumPathParser;
import io.repsy.os.server.protocols.helm.protocol.utils.HelmOciPathParser;
import io.repsy.os.server.protocols.helm.protocol.utils.HelmServerPathParser;
import io.repsy.os.server.protocols.maven.protocol.handlers.MavenPathParser;
import io.repsy.os.server.protocols.npm.protocol.handlers.NpmPathParser;
import io.repsy.os.server.protocols.nuget.protocol.handlers.NuGetPathParser;
import io.repsy.os.server.protocols.pypi.protocol.handlers.PypiPathParser;
import io.repsy.os.server.protocols.ruby.protocol.handlers.RubyPathParser;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins what every repository {@code *PathParser} accepts and rejects (RPS-2062): the repository
 * name pattern and its lower-casing, the relative path, the registry-level shortcuts of Cargo,
 * Docker and NuGet, the context a match produces, and the repository type it looks up. It is the
 * safety net for moving the parsers onto one base class.
 */
@DisplayName("Repository path parsers")
class RepoPathParsersTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  /** One parser under test with the traits that differ per protocol. */
  private record Subject(
      String label,
      RepoType type,
      String prefix,
      boolean permissiveRelativePath,
      Function<RepoTxService, PathParser> factory) {

    @Override
    public String toString() {
      return this.label;
    }
  }

  /** The nine parsers of the shape {@code <prefix>/<repo><relative path>}. */
  private static Stream<Subject> subjects() {
    return Stream.of(
        new Subject("Cargo", RepoType.CARGO, "", true, CargoPathParser::new),
        new Subject("Docker", RepoType.DOCKER, "/v2", true, DockerPathParser::new),
        new Subject("Go", RepoType.GOLANG, "", false, GoPathParser::new),
        new Subject("HelmOci", RepoType.HELM, "/v2", true, HelmOciPathParser::new),
        new Subject("HelmServer", RepoType.HELM, "", true, HelmServerPathParser::new),
        new Subject("Maven", RepoType.MAVEN, "", false, MavenPathParser::new),
        new Subject("Npm", RepoType.NPM, "", true, NpmPathParser::new),
        new Subject("NuGet", RepoType.NUGET, "", true, NuGetPathParser::new),
        new Subject("Pypi", RepoType.PYPI, "", true, PypiPathParser::new),
        new Subject("Ruby", RepoType.RUBY, "", true, RubyPathParser::new));
  }

  private static Stream<Arguments> subjectArguments() {
    return subjects().map(Arguments::of);
  }

  private static RepoInfo repoInfo(final String name) {
    return RepoInfo.builder().storageKey(STORAGE_KEY).name(name).build();
  }

  private static MockHttpServletRequest request(final String servletPath) {
    final var request = new MockHttpServletRequest();
    request.setServletPath(servletPath);
    return request;
  }

  private static UrlParserProperties urlProperties(final ProtocolContext context) {
    return context.getProperty("urlProperties");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("accepts a known repository, lower-cases its name and finds it by type")
  void acceptsAndLowerCasesRepoName(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    final var repo = repoInfo("my_repo-1");
    when(repoTxService.findRepoByNameAndType("my_repo-1", subject.type()))
        .thenReturn(Optional.of(repo));

    final var context =
        subject.factory().apply(repoTxService).parse(request(subject.prefix() + "/My_Repo-1"));

    assertThat(context).isPresent();
    final var properties = urlProperties(context.get());
    assertThat(properties.getRepoName()).isEqualTo("my_repo-1");
    assertThat(properties.getRepoInfo()).isSameAs(repo);
    assertThat(properties.getRelativePath().getPath()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("hands everything after the repository name over as the relative path")
  void relativePath(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    when(repoTxService.findRepoByNameAndType("repo", subject.type()))
        .thenReturn(Optional.of(repoInfo("repo")));
    final var parser = subject.factory().apply(repoTxService);

    final var deep = parser.parse(request(subject.prefix() + "/repo/a/b/c.txt"));
    final var slash = parser.parse(request(subject.prefix() + "/repo/"));

    assertThat(urlProperties(deep.orElseThrow()).getRelativePath().getPath())
        .isEqualTo("/a/b/c.txt");
    assertThat(urlProperties(deep.orElseThrow()).getRelativePath().getFileName())
        .isEqualTo("c.txt");
    assertThat(urlProperties(slash.orElseThrow()).getRelativePath().getPath()).isEqualTo("/");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("accepts spaces and URL metacharacters in the relative path only where it did")
  void relativePathCharacterSet(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    when(repoTxService.findRepoByNameAndType("repo", subject.type()))
        .thenReturn(Optional.of(repoInfo("repo")));
    final var parser = subject.factory().apply(repoTxService);

    for (final var tail : new String[] {"/a b", "/a#b", "/a?b", "/a&b", "/a$b", "/a{b}", "/a\\b"}) {
      final var parsed = parser.parse(request(subject.prefix() + "/repo" + tail));

      if (subject.permissiveRelativePath()) {
        assertThat(parsed).as(tail).isPresent();
        assertThat(urlProperties(parsed.get()).getRelativePath().getPath()).isEqualTo(tail);
      } else {
        assertThat(parsed).as(tail).isEmpty();
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("rejects a repository name outside [a-zA-Z0-9_-]")
  void rejectsInvalidRepoNames(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = subject.factory().apply(repoTxService);

    for (final var path : new String[] {"/bad.name/x", "/re po", "/na%me", "/na@me/x"}) {
      assertThat(parser.parse(request(subject.prefix() + path))).as(path).isEmpty();
    }

    verifyNoInteractions(repoTxService);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("returns empty for a repository that does not exist with that type")
  void unknownRepository(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);

    final var context =
        subject.factory().apply(repoTxService).parse(request(subject.prefix() + "/ghost/x"));

    assertThat(context).isEmpty();
    verify(repoTxService).findRepoByNameAndType("ghost", subject.type());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("lets an invalid storage path escape as InvalidStoragePathException")
  void dotDotRelativePath(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    when(repoTxService.findRepoByNameAndType("repo", subject.type()))
        .thenReturn(Optional.of(repoInfo("repo")));
    final var parser = subject.factory().apply(repoTxService);

    assertThatThrownBy(() -> parser.parse(request(subject.prefix() + "/repo/../etc")))
        .isInstanceOf(InvalidStoragePathException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("puts only the urlProperties on the context (NuGet also the repoInfo)")
  void contextKeys(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    when(repoTxService.findRepoByNameAndType("repo", subject.type()))
        .thenReturn(Optional.of(repoInfo("repo")));

    final var context =
        subject.factory().apply(repoTxService).parse(request(subject.prefix() + "/repo/x"));

    final var expected =
        subject.type() == RepoType.NUGET
            ? Set.of("urlProperties", "repoInfo")
            : Set.of("urlProperties");
    assertThat(context.orElseThrow().getContextMap().keySet()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("subjectArguments")
  @DisplayName("returns empty for the empty path and, where there is a prefix, a missing prefix")
  void emptyPathAndMissingPrefix(final Subject subject) {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = subject.factory().apply(repoTxService);

    assertThat(parser.parse(request(""))).isEmpty();
    if (!subject.prefix().isEmpty()) {
      assertThat(parser.parse(request("/repo/x"))).isEmpty();
      assertThat(parser.parse(request("/repo"))).isEmpty();
    }
  }

  @Test
  @DisplayName("Cargo: / and //me are registry-level, /me is the repository named me")
  void cargoRegistryLevel() {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = new CargoPathParser(repoTxService);

    for (final var path : new String[] {"/", "//", "//me", "//me/"}) {
      final var properties = urlProperties(parser.parse(request(path)).orElseThrow());
      assertThat(properties.getRepoName()).as(path).isEmpty();
      assertThat(properties.getRelativePath().getPath()).as(path).isEqualTo(path);
      assertThat(properties.getRepoInfo().getName()).as(path).isEmpty();
      assertThat(properties.getRepoInfo().getStorageKey()).as(path).isNotNull();
    }

    assertThat(parser.parse(request("/me"))).isEmpty();
    verify(repoTxService).findRepoByNameAndType("me", RepoType.CARGO);
  }

  @Test
  @DisplayName("Docker: /v2, /v2/, /v2/token and /v2/token/ are registry-level")
  void dockerRegistryLevel() {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = new DockerPathParser(repoTxService);

    for (final var path : new String[] {"/v2", "/v2/", "/v2/token", "/v2/token/"}) {
      final var properties = urlProperties(parser.parse(request(path)).orElseThrow());
      assertThat(properties.getRepoName()).as(path).isEmpty();
      assertThat(properties.getRelativePath().getPath()).as(path).isEqualTo(path);
      assertThat(properties.getRepoInfo().getName()).as(path).isEmpty();
    }

    verifyNoInteractions(repoTxService);
  }

  @Test
  @DisplayName("Docker: /v2/token/x is the repository named token, not registry-level")
  void dockerTokenPrefixIsARepoName() {
    final var repoTxService = mock(RepoTxService.class);
    final var token = repoInfo("token");
    when(repoTxService.findRepoByNameAndType("token", RepoType.DOCKER))
        .thenReturn(Optional.of(token));

    final var properties =
        urlProperties(
            new DockerPathParser(repoTxService).parse(request("/v2/token/x")).orElseThrow());

    assertThat(properties.getRepoName()).isEqualTo("token");
    assertThat(properties.getRepoInfo()).isSameAs(token);
    assertThat(properties.getRelativePath().getPath()).isEqualTo("/x");
  }

  @Test
  @DisplayName("Helm OCI: /v2/ has no registry-level shortcut")
  void helmOciHasNoRegistryLevel() {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = new HelmOciPathParser(repoTxService);

    assertThat(parser.parse(request("/v2"))).isEmpty();
    assertThat(parser.parse(request("/v2/"))).isEmpty();
    verifyNoInteractions(repoTxService);
  }

  @Test
  @DisplayName("NuGet: /v3/index.json is registry-level with only a relativePath property")
  void nugetRegistryLevel() {
    final var repoTxService = mock(RepoTxService.class);

    final var context =
        new NuGetPathParser(repoTxService).parse(request("/v3/index.json")).orElseThrow();

    assertThat(context.getContextMap().keySet()).isEqualTo(Set.of("relativePath"));
    final RelativePath relativePath = context.getProperty("relativePath");
    assertThat(relativePath.getPath()).isEqualTo("/v3/index.json");
    verifyNoInteractions(repoTxService);
  }

  @Test
  @DisplayName("NuGet: v3 is not a repository name, v3x is")
  void nugetV3IsReserved() {
    final var repoTxService = mock(RepoTxService.class);
    final var parser = new NuGetPathParser(repoTxService);
    when(repoTxService.findRepoByNameAndType("v3x", RepoType.NUGET))
        .thenReturn(Optional.of(repoInfo("v3x")));

    assertThat(parser.parse(request("/v3"))).isEmpty();
    assertThat(parser.parse(request("/v3/"))).isEmpty();
    assertThat(parser.parse(request("/v3/other.json"))).isEmpty();
    assertThat(parser.parse(request("/v3x/a"))).isPresent();
    verify(repoTxService).findRepoByNameAndType("v3x", RepoType.NUGET);
  }

  @Test
  @DisplayName("NuGet: puts the matched repoInfo on the context next to the urlProperties")
  void nugetRepoInfoProperty() {
    final var repoTxService = mock(RepoTxService.class);
    final var repo = repoInfo("nuget");
    when(repoTxService.findRepoByNameAndType("nuget", RepoType.NUGET))
        .thenReturn(Optional.of(repo));

    final var context =
        new NuGetPathParser(repoTxService).parse(request("/nuget/v3/index.json")).orElseThrow();

    final RepoInfo onContext = context.getProperty("repoInfo");
    assertThat(onContext).isSameAs(repo);
  }

  @Test
  @DisplayName("Helm ChartMuseum: /api/<repo><suffix> keeps /api in the relative path")
  void helmChartMuseum() {
    final var repoTxService = mock(RepoTxService.class);
    final var repo = repoInfo("helm");
    when(repoTxService.findRepoByNameAndType("helm", RepoType.HELM)).thenReturn(Optional.of(repo));
    final var parser = new HelmChartMuseumPathParser(repoTxService);

    final var bare = urlProperties(parser.parse(request("/api/Helm")).orElseThrow());
    final var charts = urlProperties(parser.parse(request("/api/helm/charts/x.tgz")).orElseThrow());

    assertThat(bare.getRepoName()).isEqualTo("helm");
    assertThat(bare.getRelativePath().getPath()).isEmpty();
    assertThat(charts.getRepoInfo()).isSameAs(repo);
    assertThat(charts.getRelativePath().getPath()).isEqualTo("/api/charts/x.tgz");
    assertThat(parser.parse(request("/helm/charts"))).isEmpty();
    assertThat(parser.parse(request("/api/helm/a b"))).isEmpty();
    assertThat(parser.parse(request("/api/bad.name/x"))).isEmpty();
    assertThat(parser.parse(request("/api"))).isEmpty();
    assertThat(parser.parse(request("/api/"))).isEmpty();
  }
}
