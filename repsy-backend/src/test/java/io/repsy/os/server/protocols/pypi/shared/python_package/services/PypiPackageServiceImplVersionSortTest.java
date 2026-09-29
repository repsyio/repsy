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
package io.repsy.os.server.protocols.pypi.shared.python_package.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import freemarker.template.Configuration;
import io.repsy.os.generated.model.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import io.repsy.os.server.protocols.pypi.shared.python_package.mappers.PypiPackageConverter;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseClassifierRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseProjectURLRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.ReleaseRepository;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * RPS-1688: {@code getReleaseList} whitelists a {@code version} sort but used to pass it straight
 * through to a JPA {@code ORDER BY}, which sorts the column as a string ({@code "10.0"} above
 * {@code "9.0"}). A mocked repository returns the releases in an order neither a plain string sort
 * nor insertion order would produce, and the page must still come back correct.
 */
@DisplayName("PypiPackageServiceImpl.getReleaseList version sort (RPS-1688)")
class PypiPackageServiceImplVersionSortTest {

  private final RepoRepository repoRepository = mock(RepoRepository.class);
  private final PypiStorageService pypiStorageService = mock(PypiStorageService.class);
  private final ReleaseRepository releaseRepository = mock(ReleaseRepository.class);
  private final ConversionService conversionService = mock(ConversionService.class);
  private final Configuration freeMarkerConfiguration = mock(Configuration.class);
  private final PypiPackageConverter pypiPackageConverter = mock(PypiPackageConverter.class);
  private final PypiPackageRepository pypiPackageRepository = mock(PypiPackageRepository.class);
  private final ReleaseClassifierRepository releaseClassifierRepository =
      mock(ReleaseClassifierRepository.class);
  private final ReleaseProjectURLRepository releaseProjectURLRepository =
      mock(ReleaseProjectURLRepository.class);

  private final PypiPackageServiceImpl service =
      new PypiPackageServiceImpl(
          this.repoRepository,
          this.pypiStorageService,
          this.releaseRepository,
          this.conversionService,
          this.freeMarkerConfiguration,
          this.pypiPackageConverter,
          this.pypiPackageRepository,
          this.releaseClassifierRepository,
          this.releaseProjectURLRepository);

  @Test
  @DisplayName("sorts a version-descending request by precedence, not by string or insertion order")
  void sortsByVersionPrecedenceDescending() {

    final var repoId = UUID.randomUUID();

    final var pypiPackage = new PypiPackage();
    pypiPackage.setId(UUID.randomUUID());

    when(this.pypiPackageRepository.findByRepoIdAndNormalizedName(eq(repoId), any()))
        .thenReturn(Optional.of(pypiPackage));

    // Neither insertion order nor a plain string sort ("9.0" > "10.0" > "1.2" as text) matches the
    // correct numeric-precedence order asserted below.
    final var rows = List.of(projection("9.0"), projection("10.0"), projection("1.2"));

    when(this.releaseRepository.findAllReleaseListItemsByPypiPackageId(pypiPackage.getId()))
        .thenReturn(rows);
    when(this.pypiPackageConverter.toReleaseListItemDto(any()))
        .thenAnswer(inv -> toDto(inv.getArgument(0)));

    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "version"));

    final var page = this.service.getReleaseList(repoId, "some-package", pageable);

    assertThat(page.getContent().stream().map(ReleaseListItem::getVersion))
        .containsExactly("10.0", "9.0", "1.2");
  }

  private static io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem
      projection(final String version) {
    final var row =
        mock(io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem.class);
    when(row.getVersion()).thenReturn(version);
    when(row.isFinalRelease()).thenReturn(true);
    when(row.isPreRelease()).thenReturn(false);
    when(row.isPostRelease()).thenReturn(false);
    when(row.isDevRelease()).thenReturn(false);
    when(row.getCreatedAt()).thenReturn(LocalDateTime.now());
    return row;
  }

  private static ReleaseListItem toDto(
      final io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem source) {
    return new ReleaseListItem().version(source.getVersion()).finalRelease(true);
  }
}
