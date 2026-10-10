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
package io.repsy.os.server.protocols.npm.shared.npm_package.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.generated.model.PackageVersionListItem;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.mappers.NpmPackageMapper;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageDistTagRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageKeywordRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageMaintainerRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.shared.repo.services.RepoTxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * RPS-1688: {@code getVersionsContainsVersion} whitelists a {@code version} sort but used to pass
 * it straight through to a JPA {@code ORDER BY}, which sorts the column as a string ({@code
 * "10.0.0"} above {@code "9.0.0"}). A mocked repository returns the versions in an order neither a
 * plain string sort nor insertion order would produce, and the page must still come back correct.
 */
@DisplayName("NpmPackageService.getVersionsContainsVersion version sort (RPS-1688)")
class NpmPackageServiceVersionSortTest {

  private final RepoTxService repoTxService = mock(RepoTxService.class);
  private final NpmPackageRepository npmPackageRepository = mock(NpmPackageRepository.class);
  private final PackageVersionRepository packageVersionRepository =
      mock(PackageVersionRepository.class);
  private final PackageDistTagRepository packageDistTagRepository =
      mock(PackageDistTagRepository.class);
  private final PackageMaintainerRepository packageMaintainerRepository =
      mock(PackageMaintainerRepository.class);
  private final PackageKeywordRepository packageKeywordRepository =
      mock(PackageKeywordRepository.class);
  private final NpmPackageMapper npmPackageConverter = mock(NpmPackageMapper.class);

  private final NpmPackageService service =
      new NpmPackageService(
          this.repoTxService,
          this.npmPackageRepository,
          this.packageVersionRepository,
          this.packageDistTagRepository,
          this.packageMaintainerRepository,
          this.packageKeywordRepository,
          this.npmPackageConverter);

  @Test
  @DisplayName("sorts a version-descending request by precedence, not by string or insertion order")
  void sortsByVersionPrecedenceDescending() {

    final var repoId = UUID.randomUUID();

    final var npmPackage = new NpmPackage();
    npmPackage.setId(UUID.randomUUID());

    when(this.npmPackageRepository.findByRepoIdAndScopeAndName(repoId, null, "some-package"))
        .thenReturn(Optional.of(npmPackage));

    // Neither insertion order nor a plain string sort ("9.0.0" > "10.0.0" > "1.2.0" as text)
    // matches the correct numeric-precedence order asserted below.
    final var rows = List.of(projection("9.0.0"), projection("10.0.0"), projection("1.2.0"));

    when(this.packageVersionRepository.findAllByNpmPackageIdContainsVersion(
            eq(npmPackage.getId()), any(), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(rows));
    when(this.npmPackageConverter.toPackageVersionListItemDto(any()))
        .thenAnswer(inv -> toDto(inv.getArgument(0)));

    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "version"));

    final var page =
        this.service.getVersionsContainsVersion(repoId, null, "some-package", "", pageable);

    assertThat(page.getContent().stream().map(PackageVersionListItem::getVersion))
        .containsExactly("10.0.0", "9.0.0", "1.2.0");
  }

  private static io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem
      projection(final String version) {
    final var row =
        mock(io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem.class);
    when(row.getVersion()).thenReturn(version);
    when(row.getCreatedAt()).thenReturn(LocalDateTime.now());
    when(row.getDeprecated()).thenReturn(false);
    return row;
  }

  private static PackageVersionListItem toDto(
      final io.repsy.os.server.protocols.npm.shared.npm_package.dtos.PackageVersionListItem
          source) {
    return new PackageVersionListItem().version(source.getVersion()).deprecated(false);
  }
}
