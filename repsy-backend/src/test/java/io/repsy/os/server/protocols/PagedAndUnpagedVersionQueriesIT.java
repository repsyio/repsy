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
package io.repsy.os.server.protocols;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * RPS-2062 (B11c): the version listings of five protocols read the same rows paged and unpaged (the
 * unpaged read feeds an in-memory version sort, RPS-1688). Whatever shape the unpaged read takes,
 * it must return the same rows as the paged one, and a sorted page must be a slice of the sorted
 * whole.
 */
@DisplayName("Paged and unpaged version queries read the same rows")
class PagedAndUnpagedVersionQueriesIT extends AbstractIT {

  private static final List<String> VERSIONS =
      List.of("1.9.0", "1.10.0", "2.0.0", "1.2.3", "10.0.0");
  private static final String FILTER = "1.";
  private static final String LIKE = "%1.%";
  private static final int FILTERED = 3;
  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Autowired private PypiPackageRepository pypiPackageRepository;
  @Autowired private ReleaseRepository releaseRepository;
  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;
  @Autowired private NuGetPackageRepository nugetPackageRepository;
  @Autowired private NuGetPackageVersionRepository nugetPackageVersionRepository;
  @Autowired private RubyGemRepository rubyGemRepository;
  @Autowired private RubyGemVersionRepository rubyGemVersionRepository;

  /** The unpaged read and a paged read of the same query agree on content and sorted order. */
  private static <T> void assertAgree(
      final List<T> unpaged,
      final Function<Pageable, Page<T>> paged,
      final Function<T, String> key,
      final String sortProperty,
      final int expected) {

    final var unpagedKeys = unpaged.stream().map(key).sorted().toList();
    assertThat(unpagedKeys).hasSize(expected);

    final var whole = paged.apply(PageRequest.of(0, 100, Sort.by(sortProperty)));
    assertThat(whole.getTotalElements()).isEqualTo(expected);
    // The database collation orders the strings, not Java, so the order is compared page to whole.
    final var wholeKeys = whole.getContent().stream().map(key).toList();
    assertThat(wholeKeys).containsExactlyInAnyOrderElementsOf(unpagedKeys);

    final var sliced = new ArrayList<String>();
    Pageable next = PageRequest.of(0, 2, Sort.by(sortProperty));
    Page<T> page;
    do {
      page = paged.apply(next);
      assertThat(page.getTotalElements()).isEqualTo(expected);
      page.getContent().forEach(item -> sliced.add(key.apply(item)));
      next = page.nextPageable();
    } while (page.hasNext());
    assertThat(sliced).isEqualTo(wholeKeys);

    // the key of the paged read without any sort is the same set
    final var unsorted = paged.apply(PageRequest.of(0, 100));
    assertThat(unsorted.getContent().stream().map(key).toList())
        .containsExactlyInAnyOrderElementsOf(unpagedKeys);
  }

  private PypiPackage pypi() {
    final var repo = this.seedRepo(RepoType.PYPI, uniqueRepoName("pypi"));
    final var pkg = new PypiPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setName("demo");
    pkg.setNormalizedName("demo");
    pkg.setCreatedAt(NOW);
    final var saved = this.pypiPackageRepository.saveAndFlush(pkg);

    VERSIONS.forEach(
        version -> {
          final var release = new Release();
          release.setPypiPackage(saved);
          release.setVersion(version);
          release.setFinalRelease(true);
          release.setCreatedAt(NOW);
          this.releaseRepository.saveAndFlush(release);
        });
    this.entityManager.clear();
    return saved;
  }

  @Test
  @DisplayName("pypi releases, with and without a name filter")
  void pypiReleases() {
    final var pkg = this.pypi();
    final UUID id = pkg.getId();

    assertAgree(
        this.releaseRepository.findAllReleaseListItemsByPypiPackageId(id),
        pageable -> this.releaseRepository.findAllByPypiPackageId(id, pageable),
        item -> item.getVersion(),
        "version",
        VERSIONS.size());

    assertAgree(
        this.releaseRepository.findAllByPypiPackageIdContainsName(id, FILTER),
        pageable -> this.releaseRepository.findAllByPypiPackageIdContainsName(id, FILTER, pageable),
        item -> item.getVersion(),
        "version",
        FILTERED);
  }

  @Test
  @DisplayName("maven artifact versions, with and without a version filter")
  void mavenArtifactVersions() {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("maven"));
    final var artifact = new Artifact();
    artifact.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    artifact.setGroupName("com.acme");
    artifact.setArtifactName("lib");
    artifact.setCreatedAt(NOW);
    artifact.setLastUpdatedAt(NOW);
    final var saved = this.artifactRepository.saveAndFlush(artifact);

    VERSIONS.forEach(
        version -> {
          final var row = new ArtifactVersion();
          row.setArtifact(saved);
          row.setType(ArtifactVersionType.RELEASE);
          row.setVersionName(version);
          row.setCreatedAt(NOW);
          row.setLastUpdatedAt(NOW);
          this.artifactVersionRepository.saveAndFlush(row);
        });
    this.entityManager.clear();

    final var repoId = repo.getId();

    assertAgree(
        this.artifactVersionRepository.findAllByRepoIdAndGroupNameAndArtifactName(
            repoId, "com.acme", "lib"),
        pageable ->
            this.artifactVersionRepository.findAllByRepoIdAndGroupNameAndArtifactName(
                repoId, "com.acme", "lib", pageable),
        item -> item.getVersionName(),
        "versionName",
        VERSIONS.size());

    assertAgree(
        this.artifactVersionRepository
            .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
                repoId, "com.acme", "lib", FILTER),
        pageable ->
            this.artifactVersionRepository
                .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
                    repoId, "com.acme", "lib", FILTER, pageable),
        item -> item.getVersionName(),
        "versionName",
        FILTERED);
  }

  @Test
  @DisplayName("npm package versions with a version filter")
  void npmVersions() {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var pkg = new NpmPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setName("demo");
    pkg.setCreatedAt(NOW);
    final var saved = this.npmPackageRepository.saveAndFlush(pkg);

    VERSIONS.forEach(
        version -> {
          final var row = new PackageVersion();
          row.setNpmPackage(saved);
          row.setVersion(version);
          row.setCreatedAt(NOW);
          this.packageVersionRepository.saveAndFlush(row);
        });
    this.entityManager.clear();
    final var id = saved.getId();

    assertAgree(
        this.packageVersionRepository.findAllByNpmPackageIdContainsVersion(id, LIKE),
        pageable ->
            this.packageVersionRepository.findAllByNpmPackageIdContainsVersion(id, LIKE, pageable),
        item -> item.getVersion(),
        "version",
        FILTERED);
  }

  @Test
  @DisplayName("nuget package versions with a version filter")
  void nugetVersions() {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget"));
    final var pkg = new NuGetPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setPackageId("Demo");
    pkg.setCreatedAt(NOW);
    final var saved = this.nugetPackageRepository.saveAndFlush(pkg);

    VERSIONS.forEach(
        version -> {
          final var row = new NuGetPackageVersion();
          row.setNugetPackage(saved);
          row.setVersion(version);
          row.setListed(true);
          row.setPublishedAt(NOW);
          row.setCreatedAt(NOW);
          this.nugetPackageVersionRepository.saveAndFlush(row);
        });
    this.entityManager.clear();
    final var id = saved.getId();

    assertAgree(
        this.nugetPackageVersionRepository.searchByNugetPackageId(id, LIKE),
        pageable -> this.nugetPackageVersionRepository.searchByNugetPackageId(id, LIKE, pageable),
        item -> item.getVersion(),
        "version",
        FILTERED);
  }

  @Test
  @DisplayName("ruby gem versions, with and without a version filter")
  void rubyVersions() {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gem = new RubyGem();
    gem.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    gem.setName("demo");
    gem.setLatest("10.0.0");
    gem.setCreatedAt(NOW);
    final var saved = this.rubyGemRepository.saveAndFlush(gem);

    VERSIONS.forEach(
        version -> {
          final var row = new RubyGemVersion();
          row.setGem(saved);
          row.setVersion(version);
          row.setPlatform("ruby");
          row.setChecksum("0".repeat(64));
          row.setCreatedAt(NOW);
          this.rubyGemVersionRepository.saveAndFlush(row);
        });
    this.entityManager.clear();
    final var id = saved.getId();

    assertAgree(
        this.rubyGemVersionRepository.findAllByGemId(id, null),
        pageable -> this.rubyGemVersionRepository.findAllByGemId(id, null, pageable),
        item -> item.getVersion(),
        "version",
        VERSIONS.size());

    assertAgree(
        this.rubyGemVersionRepository.findAllByGemId(id, LIKE),
        pageable -> this.rubyGemVersionRepository.findAllByGemId(id, LIKE, pageable),
        item -> item.getVersion(),
        "version",
        FILTERED);
  }
}
