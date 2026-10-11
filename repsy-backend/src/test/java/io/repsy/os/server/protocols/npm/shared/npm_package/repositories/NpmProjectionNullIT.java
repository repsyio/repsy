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

package io.repsy.os.server.protocols.npm.shared.npm_package.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageMaintainer;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

/**
 * RPS-2144: under a package-level {@code @NullMarked} Spring Data refuses a null column behind a
 * projection getter that is not {@code @Nullable} ({@code Return value is null but must not be
 * null}). A maintainer is published with only a name, so its email and url are null; removing the
 * {@code @Nullable} of the three getters makes the reads below fail.
 */
@DisplayName("npm projections read null columns")
class NpmProjectionNullIT extends AbstractIT {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository packageVersionRepository;
  @Autowired private PackageMaintainerRepository packageMaintainerRepository;

  @Test
  @DisplayName("a maintainer without an email and url reads as null through both projections")
  void maintainerWithoutEmailAndUrl() {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var pkg = new NpmPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setName("demo");
    pkg.setCreatedAt(NOW);
    final var savedPackage = this.npmPackageRepository.saveAndFlush(pkg);

    final var version = new PackageVersion();
    version.setNpmPackage(savedPackage);
    version.setVersion("1.0.0");
    version.setCreatedAt(NOW);
    final var savedVersion = this.packageVersionRepository.saveAndFlush(version);

    final var maintainer = new PackageMaintainer();
    maintainer.setPackageVersion(savedVersion);
    maintainer.setName("jane");
    maintainer.setCreatedAt(NOW);
    this.packageMaintainerRepository.saveAndFlush(maintainer);
    this.entityManager.clear();

    final var maintainers =
        this.packageMaintainerRepository.findAllByPackageVersionId(savedVersion.getId());
    final var rows =
        this.packageMaintainerRepository.findAllByPackageVersionIdIn(List.of(savedVersion.getId()));

    assertThat(maintainers).hasSize(1);
    assertThat(maintainers.getFirst().getName()).isEqualTo("jane");
    assertThat(maintainers.getFirst().getEmail()).isNull();
    assertThat(maintainers.getFirst().getUrl()).isNull();
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getEmail()).isNull();
  }

  @Test
  @DisplayName("a package without a latest version is not listed, so the list never reads a null")
  void packageWithoutLatestIsNotListed() {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("npm"));
    final var pkg = new NpmPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setName("demo");
    pkg.setCreatedAt(NOW);
    final var savedPackage = this.npmPackageRepository.saveAndFlush(pkg);

    final var version = new PackageVersion();
    version.setNpmPackage(savedPackage);
    version.setVersion("1.0.0");
    version.setCreatedAt(NOW);
    this.packageVersionRepository.saveAndFlush(version);
    this.entityManager.clear();

    assertThat(
            this.npmPackageRepository.findAllByRepoIdAndLatestVersionContainsScope(
                repo.getId(), null, Pageable.unpaged()))
        .isEmpty();
  }
}
