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
package io.repsy.protocols.nuget.protocol.facades;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static io.repsy.protocols.nuget.NuGetTestContexts.repoInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetProtocolFacade search and autocomplete")
class AbstractNuGetProtocolFacadeSearchTest {

  private static final String BASE_URL = "http://localhost/nuget";

  @Mock private NuGetStorageService storageService;
  @Mock private NuGetPackageService<UUID> packageService;

  private final BaseRepoInfo<UUID> info = repoInfo();
  private final ProtocolContext ctx = context("/nuget/v3/search", this.info);

  private AbstractNuGetProtocolFacade<UUID> facade;

  @BeforeEach
  void setUp() {
    this.facade = new AbstractNuGetProtocolFacade<>(this.storageService, this.packageService) {};
  }

  @Test
  @DisplayName("passes the semVerLevel opt-in on to the package search (RPS-1275)")
  void searchPassesTheOptIn() {
    final var result =
        new NuGetPackageSearchResult(
            "Some.Package",
            "1.0.0",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            List.of(new NuGetPackageSearchResult.VersionSummary("1.0.0", 0)));
    when(this.packageService.search(this.info, "some", 0, 20, false, false))
        .thenReturn(new PageImpl<>(List.of(result)));

    final var response = this.facade.search(this.ctx, "some", 0, 20, false, false, BASE_URL);

    assertThat(response.totalHits()).isEqualTo(1);
    assertThat(response.data())
        .singleElement()
        .satisfies(d -> assertThat(d.packageId()).isEqualTo("Some.Package"));
    verify(this.packageService).search(this.info, "some", 0, 20, false, false);
  }

  @Test
  @DisplayName("lists the versions of a package without the SemVer 2.0.0-only ones by default")
  void autocompleteVersionsWithoutOptIn() {
    when(this.packageService.getVersions(this.info, "some.package"))
        .thenReturn(List.of("1.0.0", "1.1.0-beta", "1.1.0-beta.2", "1.2.0+build", "2.0.0-rc.1"));

    final var without = this.facade.autocomplete(this.ctx, "", "some.package", 0, 20, true, false);
    final var withoutPrerelease =
        this.facade.autocomplete(this.ctx, "", "some.package", 0, 20, false, false);

    assertThat(without.data()).containsExactly("1.0.0", "1.1.0-beta");
    assertThat(without.totalHits()).isEqualTo(2);
    assertThat(withoutPrerelease.data()).containsExactly("1.0.0");
  }

  @Test
  @DisplayName("lists every version of a package for a client that opted in to SemVer 2.0.0")
  void autocompleteVersionsWithOptIn() {
    when(this.packageService.getVersions(this.info, "some.package"))
        .thenReturn(List.of("1.0.0", "1.1.0-beta", "1.1.0-beta.2", "1.2.0+build", "2.0.0-rc.1"));

    final var withPrerelease =
        this.facade.autocomplete(this.ctx, "", "some.package", 0, 20, true, true);
    final var withoutPrerelease =
        this.facade.autocomplete(this.ctx, "", "some.package", 0, 20, false, true);

    assertThat(withPrerelease.data())
        .containsExactly("1.0.0", "1.1.0-beta", "1.1.0-beta.2", "1.2.0+build", "2.0.0-rc.1");
    assertThat(withoutPrerelease.data()).containsExactly("1.0.0", "1.2.0+build");
  }

  @Test
  @DisplayName("passes the semVerLevel opt-in on to the package id autocomplete (RPS-1275)")
  void autocompleteIdsPassesTheOptIn() {
    when(this.packageService.autocomplete(this.info, "some", 0, 20, false, false))
        .thenReturn(List.of("Some.Package"));

    final var response = this.facade.autocomplete(this.ctx, "some", null, 0, 20, false, false);

    assertThat(response.data()).containsExactly("Some.Package");
    verify(this.packageService).autocomplete(this.info, "some", 0, 20, false, false);
  }
}
