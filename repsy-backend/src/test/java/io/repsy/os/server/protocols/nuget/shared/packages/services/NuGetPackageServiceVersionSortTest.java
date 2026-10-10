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
package io.repsy.os.server.protocols.nuget.shared.packages.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackageVersion;
import io.repsy.os.server.protocols.nuget.shared.packages.mappers.NuGetPackageMapper;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetVersionInfo;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
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
 * RPS-1688: {@code getVersionInfosPage} whitelists a {@code version} sort but used to pass it
 * straight through to a JPA {@code ORDER BY}, which sorts the column as a string ({@code "10.0.0"}
 * above {@code "9.0.0"}). This proves the fix at the service layer, not just in the {@code
 * NuGetVersionUtils.VERSION_COMPARATOR} it now sorts with (already covered by {@code
 * NuGetPackageUtilsTest}): a mocked repository returns the versions in an order neither a plain
 * string sort nor insertion order would produce, and the page must still come back correct.
 */
@DisplayName("NuGetPackageService.getVersionInfosPage version sort (RPS-1688)")
class NuGetPackageServiceVersionSortTest {

  private final NuGetPackageRepository packageRepository = mock(NuGetPackageRepository.class);
  private final NuGetPackageVersionRepository packageVersionRepository =
      mock(NuGetPackageVersionRepository.class);
  private final NuGetPackageMapper converter = mock(NuGetPackageMapper.class);

  private final NuGetPackageService service =
      new NuGetPackageService(
          this.packageRepository, this.packageVersionRepository, this.converter);

  @Test
  @DisplayName("sorts a version-descending request by precedence, not by string or insertion order")
  void sortsByVersionPrecedenceDescending() {

    final var repoId = UUID.randomUUID();
    final var repoInfo =
        BaseRepoInfo.<UUID>builder()
            .id(repoId)
            .name("nuget-repo")
            .allowOverride(false)
            .type(RepoType.NUGET)
            .build();

    final var pkg = new NuGetPackage();
    pkg.setId(UUID.randomUUID());
    pkg.setPackageId("some.package");

    when(this.packageRepository.findByRepoIdAndPackageIdIgnoreCase(repoId, "some.package"))
        .thenReturn(Optional.of(pkg));

    // Neither insertion order nor a plain string sort ("9.0.0" > "10.0.0" > "1.2.0" as text)
    // matches the correct numeric-precedence order asserted below.
    final var entities =
        List.of(
            versionEntity(pkg, "9.0.0"), versionEntity(pkg, "10.0.0"), versionEntity(pkg, "1.2.0"));

    when(this.packageVersionRepository.searchByNugetPackageId(
            eq(pkg.getId()), any(), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(entities));
    when(this.converter.toVersionInfo(any(), eq("Some.Package")))
        .thenAnswer(inv -> toInfo(inv.getArgument(0)));

    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "version"));

    final var page = this.service.getVersionInfosPage(repoInfo, "Some.Package", "", pageable);

    assertThat(page.getContent().stream().map(NuGetVersionInfo::version))
        .containsExactly("10.0.0", "9.0.0", "1.2.0");
  }

  private static NuGetPackageVersion versionEntity(final NuGetPackage pkg, final String version) {
    final var entity = new NuGetPackageVersion();
    entity.setId(UUID.randomUUID());
    entity.setNugetPackage(pkg);
    entity.setVersion(version);
    return entity;
  }

  private static NuGetVersionInfo toInfo(final NuGetPackageVersion entity) {
    return new NuGetVersionInfo(
        "Some.Package",
        entity.getVersion(),
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
        Instant.EPOCH,
        null,
        null,
        null);
  }
}
