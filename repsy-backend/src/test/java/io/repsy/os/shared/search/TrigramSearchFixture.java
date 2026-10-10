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
package io.repsy.os.shared.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.core.web.utils.LikePatterns;
import io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem;
import io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmPackageListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * The contains-searches of the panel lists (RPS-2117) against rows that tell the cases apart: the
 * term matches regardless of case, and its {@code %} and {@code _} are literal characters. The same
 * rows and expectations run on PostgreSQL (where the trigram indexes serve the query) and on H2
 * (where the same JPQL is a plain LIKE), so the rewrite to an indexable expression cannot change a
 * result on either.
 */
final class TrigramSearchFixture {

  private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

  private final EntityManager entityManager;
  private final ArtifactRepository artifactRepository;
  private final NpmPackageRepository npmPackageRepository;
  private final PypiPackageRepository pypiPackageRepository;
  private final GoModuleRepository goModuleRepository;
  private final UserRepository userRepository;

  TrigramSearchFixture(
      final EntityManager entityManager,
      final ArtifactRepository artifactRepository,
      final NpmPackageRepository npmPackageRepository,
      final PypiPackageRepository pypiPackageRepository,
      final GoModuleRepository goModuleRepository,
      final UserRepository userRepository) {
    this.entityManager = entityManager;
    this.artifactRepository = artifactRepository;
    this.npmPackageRepository = npmPackageRepository;
    this.pypiPackageRepository = pypiPackageRepository;
    this.goModuleRepository = goModuleRepository;
    this.userRepository = userRepository;
  }

  void assertMavenSearch() {
    final var repo = this.repo(RepoType.MAVEN);
    this.artifact(repo, "Org.Acme", "Core-Lib");
    this.artifact(repo, "org.acme", "utils");
    this.artifact(repo, "com.other", "acme-tool");
    this.artifact(repo, "com.pct", "a_b");
    this.artifact(repo, "com.pct", "axb");
    this.flush();

    assertThat(this.groupSearch(repo, "ACME"))
        .containsExactlyInAnyOrder("Core-Lib", "utils", "acme-tool");
    assertThat(this.groupSearch(repo, "org.acme:CORE")).containsExactly("Core-Lib");
    assertThat(this.groupSearch(repo, "a_b")).containsExactly("a_b");
    assertThat(this.groupSearch(repo, "a%b")).isEmpty();
    assertThat(this.groupSearch(repo, "nothing")).isEmpty();
    assertThat(
            names(
                this.artifactRepository.findAllByRepoIdContainsArtifactName(
                    repo.getId(),
                    "Org.Acme",
                    LikePatterns.of("%", "CORE", "%"),
                    PageRequest.of(0, 10)),
                ArtifactListItem::getArtifactName))
        .containsExactly("Core-Lib");
  }

  void assertNpmSearch() {
    final var repo = this.repo(RepoType.NPM);
    this.npmPackage(repo, null, "Left-Pad");
    this.npmPackage(repo, "acme", "Widget");
    this.npmPackage(repo, "acme", "gadget");
    this.flush();
    final var page = PageRequest.of(0, 10);

    assertThat(
            names(
                this.npmPackageRepository.findAllByRepoIdAndLatestVersionAndScopeIsNullContainsName(
                    repo.getId(), LikePatterns.of("%", "LEFT", "%"), page),
                NpmPackageListItem::getName))
        .containsExactly("Left-Pad");
    assertThat(
            names(
                this.npmPackageRepository.findAllByRepoIdAndLatestVersionAndScopeContainsName(
                    repo.getId(), "acme", LikePatterns.of("%", "wid", "%"), page),
                NpmPackageListItem::getName))
        .containsExactly("Widget");
    // The list search matches the whole @scope/name key, and the bare name of an unscoped package.
    assertThat(this.npmList(repo, "ACME")).containsExactlyInAnyOrder("Widget", "gadget");
    assertThat(this.npmList(repo, "acme/w")).containsExactly("Widget");
    assertThat(this.npmList(repo, "left")).containsExactly("Left-Pad");
    assertThat(this.npmList(repo, "a_me")).isEmpty();
    assertThat(
            names(
                this.npmPackageRepository.findAllByRepoIdAndLatestVersionContainsScope(
                    repo.getId(), null, page),
                NpmPackageListItem::getName))
        .containsExactlyInAnyOrder("Left-Pad", "Widget", "gadget");
  }

  void assertPypiSearch() {
    final var repo = this.repo(RepoType.PYPI);
    this.pypiPackage(repo, "Django-Ext");
    this.pypiPackage(repo, "flask");
    this.flush();

    assertThat(
            names(
                this.pypiPackageRepository.findAllByRepoIdContainsName(
                    repo.getId(), LikePatterns.of("%", "DJANGO", "%"), PageRequest.of(0, 10)),
                item -> item.getName()))
        .containsExactly("Django-Ext");
    assertThat(
            this.pypiPackageRepository
                .findAllByRepoIdContainsName(
                    repo.getId(), LikePatterns.of("%", "d_ngo", "%"), PageRequest.of(0, 10))
                .getContent())
        .isEmpty();
  }

  void assertGoSearch() {
    final var repo = this.repo(RepoType.GOLANG);
    this.goModule(repo, "github.com/Acme/Tool");
    this.goModule(repo, "github.com/other/acme");
    this.goModule(repo, "example.com/x_y");
    this.goModule(repo, "example.com/xzy");
    this.flush();
    final var page = PageRequest.of(0, 10);

    assertThat(
            names(
                this.goModuleRepository.findAllByRepoIdContainsModulePath(
                    repo.getId(), LikePatterns.of("%", "ACME", "%"), page),
                GoModuleListItem::getModulePath))
        .containsExactlyInAnyOrder("github.com/Acme/Tool", "github.com/other/acme");
    assertThat(
            names(
                this.goModuleRepository.findAllByRepoIdContainsModulePath(
                    repo.getId(), LikePatterns.of("%", "x_y", "%"), page),
                GoModuleListItem::getModulePath))
        .containsExactly("example.com/x_y");
  }

  void assertUserSearch() {
    final var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    this.user("Zq" + suffix + "Admin");
    this.user("zq" + suffix + "_two");
    this.user("bob" + suffix);
    this.flush();
    final var page = PageRequest.of(0, 50);
    final Function<String, List<String>> search =
        term ->
            this.userRepository.findAllWithSearch(LikePatterns.of("%", term, "%"), page).stream()
                .map(User::getUsername)
                .toList();

    assertThat(search.apply("ZQ" + suffix))
        .containsExactlyInAnyOrder("Zq" + suffix + "Admin", "zq" + suffix + "_two");
    assertThat(search.apply("zq" + suffix + "_t")).containsExactly("zq" + suffix + "_two");
    assertThat(search.apply("zq" + suffix + "xt")).isEmpty();
    assertThat(this.userRepository.findAllWithSearch(null, page).getContent())
        .extracting(User::getUsername)
        .contains("bob" + suffix);
  }

  private List<String> groupSearch(final Repo repo, final String term) {
    return names(
        this.artifactRepository.findAllByRepoIdAndContainsGroupName(
            repo.getId(), LikePatterns.of("%", term, "%"), PageRequest.of(0, 10)),
        ArtifactListItem::getArtifactName);
  }

  private List<String> npmList(final Repo repo, final String term) {
    return names(
        this.npmPackageRepository.findAllByRepoIdAndLatestVersionContainsScope(
            repo.getId(), LikePatterns.of("%", term, "%"), PageRequest.of(0, 10)),
        NpmPackageListItem::getName);
  }

  private static <T> List<String> names(final Page<T> page, final Function<T, String> name) {
    return page.getContent().stream().map(name).toList();
  }

  private void flush() {
    this.entityManager.flush();
    this.entityManager.clear();
  }

  private Repo repo(final RepoType type) {
    final var repo = new Repo();
    repo.setName("trgm" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    repo.setType(type);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(NOW);
    this.entityManager.persist(repo);

    return repo;
  }

  private void artifact(final Repo repo, final String group, final String name) {
    final var artifact = new Artifact();
    artifact.setRepo(repo);
    artifact.setGroupName(group);
    artifact.setArtifactName(name);
    artifact.setName(name);
    artifact.setPackaging("jar");
    artifact.setPlugin(false);
    artifact.setLatest("1.0.0");
    artifact.setRelease("1.0.0");
    artifact.setLastUpdatedAt(NOW);
    this.entityManager.persist(artifact);
  }

  private void npmPackage(final Repo repo, final String scope, final String name) {
    final var pkg = new NpmPackage();
    pkg.setRepo(repo);
    pkg.setScope(scope);
    pkg.setName(name);
    pkg.setLatest("1.0.0");
    pkg.setCreatedAt(NOW);
    this.entityManager.persist(pkg);
    final var version = new PackageVersion();
    version.setNpmPackage(pkg);
    version.setVersion("1.0.0");
    version.setCreatedAt(NOW);
    this.entityManager.persist(version);
  }

  private void pypiPackage(final Repo repo, final String name) {
    final var pkg = new PypiPackage();
    pkg.setRepo(repo);
    pkg.setName(name);
    pkg.setNormalizedName(name.toLowerCase(java.util.Locale.ROOT));
    pkg.setLatestVersion("1.0");
    pkg.setStableVersion("1.0");
    pkg.setCreatedAt(NOW);
    this.entityManager.persist(pkg);
    final var release = new Release();
    release.setPypiPackage(pkg);
    release.setVersion("1.0");
    release.setCreatedAt(NOW);
    this.entityManager.persist(release);
  }

  private void goModule(final Repo repo, final String path) {
    final var module = new GoModule();
    module.setRepo(repo);
    module.setModulePath(path);
    module.setCreatedAt(NOW);
    this.entityManager.persist(module);
  }

  private void user(final String username) {
    final var user = new User();
    user.setUsername(username);
    user.setHash("x".repeat(60));
    user.setRole(UserRole.USER);
    user.setCreatedAt(NOW);
    this.entityManager.persist(user);
  }
}
