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
package io.repsy.os.server.protocols.nuget.ui.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.generated.model.NuGetDeletedItem;
import io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetPackageServiceImpl;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetPackageSearchResult;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
@DisplayName("NuGetApiFacade")
class NuGetApiFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final RepoInfo REPO_INFO =
      RepoInfo.builder()
          .id(REPO_ID)
          .storageKey(UUID.fromString("00000000-0000-0000-0000-000000000002"))
          .name("nuget")
          .type(RepoType.NUGET)
          .build();

  @Mock private NuGetPackageServiceImpl nugetPackageService;
  @Mock private NuGetStorageService nugetStorageService;
  @Mock private ApplicationEventPublisher eventPublisher;

  private NuGetApiFacade facade;

  @BeforeEach
  void setUp() {
    facade = new NuGetApiFacade(nugetPackageService, nugetStorageService, eventPublisher);
  }

  @Nested
  @DisplayName("search()")
  class SearchTests {

    @Test
    @DisplayName("passes the requested page and sort to the package service")
    void passesPageableThrough() {
      final var pageable = PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "packageId"));
      when(nugetPackageService.searchPage(REPO_INFO, "fixture", pageable, false))
          .thenReturn(
              new PageImpl<>(
                  List.of(
                      new NuGetPackageSearchResult(
                          "some.package",
                          "1.0.0",
                          null,
                          "desc",
                          null,
                          null,
                          null,
                          null,
                          null,
                          7L,
                          List.of())),
                  pageable,
                  11));

      final var page = facade.search(REPO_INFO, "fixture", pageable);

      assertThat(page.getNumber()).isEqualTo(2);
      assertThat(page.getTotalElements()).isEqualTo(11);
      assertThat(page.getContent())
          .singleElement()
          .satisfies(
              item -> {
                assertThat(item.getPackageId()).isEqualTo("some.package");
                assertThat(item.getLatestVersion()).isEqualTo("1.0.0");
                assertThat(item.getTotalDownloads()).isEqualTo(7L);
              });
    }
  }

  @Nested
  @DisplayName("deletePackage()")
  class DeletePackageTests {

    @Test
    @DisplayName(
        "deletes the package from storage under its lower-cased id and reports the freed disk")
    void deletesStorageUnderLowerCasedId() throws IOException {
      when(nugetStorageService.deletePackage(REPO_ID, "some.package")).thenReturn(100L);

      final var usages = facade.deletePackage(REPO_INFO, "Some.Package");

      verify(nugetPackageService).deletePackage(REPO_INFO, "Some.Package");
      assertThat(usages.getDiskUsage()).isEqualTo(-100L);
    }

    @Test
    @DisplayName("still succeeds when the storage delete fails")
    void toleratesStorageFailure() throws IOException {
      when(nugetStorageService.deletePackage(REPO_ID, "some.package"))
          .thenThrow(new IOException("disk error"));

      final var usages = facade.deletePackage(REPO_INFO, "Some.Package");

      assertThat(usages.getDiskUsage()).isZero();
    }
  }

  @Nested
  @DisplayName("deleteVersion()")
  class DeleteVersionTests {

    @Test
    @DisplayName("removes the version files, then the emptied package directory")
    void removesPackageDirectoryWhenLastVersionIsDeleted() throws IOException {
      when(nugetPackageService.deleteVersionAndGetDeletedItem(
              REPO_INFO, "Some.Package", "1.0.0-RC1"))
          .thenReturn(NuGetDeletedItem.PACKAGE);
      when(nugetStorageService.deletePackageVersion(REPO_ID, "some.package", "1.0.0-rc1"))
          .thenReturn(50L);

      final var result = facade.deleteVersion(REPO_INFO, "Some.Package", "1.0.0-RC1");

      verify(nugetStorageService).deletePackage(REPO_ID, "some.package");
      verify(eventPublisher).publishEvent(any(ArtifactVersionDeletedEvent.class));
      assertThat(result.deletedItem()).isEqualTo(NuGetDeletedItem.PACKAGE);
      assertThat(result.usages().getDiskUsage()).isEqualTo(-50L);
    }

    @Test
    @DisplayName("ignores a failure to clean up the package directory")
    void ignoresDirectoryCleanupFailure() throws IOException {
      when(nugetPackageService.deleteVersionAndGetDeletedItem(REPO_INFO, "Some.Package", "1.0.0"))
          .thenReturn(NuGetDeletedItem.PACKAGE);
      when(nugetStorageService.deletePackageVersion(REPO_ID, "some.package", "1.0.0"))
          .thenReturn(50L);
      doThrow(new IOException("not empty"))
          .when(nugetStorageService)
          .deletePackage(REPO_ID, "some.package");

      final var result = facade.deleteVersion(REPO_INFO, "Some.Package", "1.0.0");

      assertThat(result.usages().getDiskUsage()).isEqualTo(-50L);
    }

    @Test
    @DisplayName("keeps the package directory while other versions remain")
    void keepsPackageDirectoryWhenVersionsRemain() throws IOException {
      when(nugetPackageService.deleteVersionAndGetDeletedItem(REPO_INFO, "Some.Package", "1.0.0"))
          .thenReturn(NuGetDeletedItem.VERSION);
      when(nugetStorageService.deletePackageVersion(REPO_ID, "some.package", "1.0.0"))
          .thenReturn(30L);

      final var result = facade.deleteVersion(REPO_INFO, "Some.Package", "1.0.0");

      verify(nugetStorageService, never()).deletePackage(any(), any());
      assertThat(result.deletedItem()).isEqualTo(NuGetDeletedItem.VERSION);
      assertThat(result.usages().getDiskUsage()).isEqualTo(-30L);
    }

    @Test
    @DisplayName("still succeeds when the storage delete fails")
    void toleratesStorageFailure() throws IOException {
      when(nugetPackageService.deleteVersionAndGetDeletedItem(REPO_INFO, "Some.Package", "1.0.0"))
          .thenReturn(NuGetDeletedItem.VERSION);
      when(nugetStorageService.deletePackageVersion(REPO_ID, "some.package", "1.0.0"))
          .thenThrow(new IOException("disk error"));

      final var result = facade.deleteVersion(REPO_INFO, "Some.Package", "1.0.0");

      assertThat(result.usages().getDiskUsage()).isZero();
    }
  }
}
