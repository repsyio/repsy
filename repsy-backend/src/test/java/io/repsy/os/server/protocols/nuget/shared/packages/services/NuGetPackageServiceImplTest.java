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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.nuget.shared.packages.entities.NuGetPackage;
import io.repsy.os.server.protocols.nuget.shared.packages.mappers.NuGetPackageConverter;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageRepository;
import io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetPackageVersionRepository;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import io.repsy.protocols.nuget.shared.utils.NuGetPackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * RPS-1146: {@code createNuGetPackageVersion} used to catch a failure serializing the parsed
 * dependencies to JSON, warn, and store the version with a {@code null} dependencies column anyway.
 * These tests force that serialization to fail (there is no realistic nuspec input that does, since
 * a dependency is a handful of plain strings, so the failure is injected through a mocked {@link
 * NuGetPackageUtils#toDependenciesJson}) and assert the push now fails instead.
 */
@DisplayName("NuGetPackageServiceImpl.publishVersion dependency serialization (RPS-1146)")
class NuGetPackageServiceImplTest {

  private final NuGetPackageRepository packageRepository = mock(NuGetPackageRepository.class);
  private final NuGetPackageVersionRepository packageVersionRepository =
      mock(NuGetPackageVersionRepository.class);
  private final NuGetPackageConverter converter = mock(NuGetPackageConverter.class);

  private final NuGetPackageServiceImpl service =
      new NuGetPackageServiceImpl(
          this.packageRepository, this.packageVersionRepository, this.converter);

  private static final String NUSPEC_WITH_DEPENDENCY =
      """
      <package><metadata><id>Some.Package</id><version>1.0.0</version>
        <dependencies><dependency id="Serilog" version="3.1.1" /></dependencies>
      </metadata></package>
      """;

  @Test
  @DisplayName("fails the push instead of storing null dependencies when they cannot be serialized")
  void failsPushInsteadOfStoringNullDependencies() {
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
    when(this.packageVersionRepository.findByNugetPackageIdAndVersion(pkg.getId(), "1.0.0"))
        .thenReturn(Optional.empty());

    try (MockedStatic<NuGetPackageUtils> utils =
        mockStatic(NuGetPackageUtils.class, Mockito.CALLS_REAL_METHODS)) {
      utils
          .when(() -> NuGetPackageUtils.toDependenciesJson(any()))
          .thenThrow(new IllegalStateException("mapper misconfigured"));

      assertThatThrownBy(
              () ->
                  this.service.publishVersion(
                      repoInfo,
                      "Some.Package",
                      "1.0.0",
                      NUSPEC_WITH_DEPENDENCY,
                      null,
                      replacesExisting -> {
                        throw new AssertionError("files must not be written when the row fails");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("mapper misconfigured");
    }

    verify(this.packageVersionRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("still stores dependencies that serialize without error")
  void storesDependenciesThatSerialize() throws Exception {
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
    when(this.packageVersionRepository.findByNugetPackageIdAndVersion(pkg.getId(), "1.0.0"))
        .thenReturn(Optional.empty());
    when(this.packageVersionRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    this.service.publishVersion(
        repoInfo, "Some.Package", "1.0.0", NUSPEC_WITH_DEPENDENCY, null, replacesExisting -> null);

    verify(this.packageVersionRepository)
        .saveAndFlush(
            ArgumentMatchers.argThat(
                v ->
                    v.getDependencies() != null
                        && NuGetPackageUtils.parseDependenciesJson(
                                v.getDependencies(), "Some.Package", "1.0.0")
                            .equals(List.of(new NuGetDependencyInfo("Serilog", "3.1.1", null)))));
  }
}
