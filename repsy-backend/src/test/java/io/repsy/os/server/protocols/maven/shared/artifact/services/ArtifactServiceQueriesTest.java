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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.PLUGIN;
import static io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType.RELEASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.web.utils.LikePatterns;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionListItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.mappers.ArtifactMapper;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.PendingSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionSignatureRepository;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.maven.shared.keystore.services.PgpVerifierService;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pins the read side of the Maven artifact services (RPS-2064): the queries of the panel and of the
 * delete flow, their paging and sort rules and their not-found codes. The assertions are the same
 * before and after the class was split, only the object under test changes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Maven artifact queries (RPS-2064 characterization)")
class ArtifactServiceQueriesTest {

  private static final UUID REPO_ID = UUID.randomUUID();

  @Mock ArtifactRepository artifactRepository;
  @Mock ArtifactVersionRepository artifactVersionRepository;
  @Mock ArtifactMapper artifactConverter;
  @Mock StorageStrategyRegistry storageStrategyRegistry;
  @Mock PgpVerifierService pgpVerifierService;
  @Mock KeyStoreService keyStoreService;
  @Mock RepoTxService repoTxService;
  @Mock VersionDeveloperRepository versionDeveloperRepository;
  @Mock VersionLicenseRepository versionLicenseRepository;
  @Mock ArtifactUpsertHelper artifactUpsertHelper;
  @Mock ArtifactVersionWriteService artifactVersionWriteService;
  @Mock VersionSignatureRepository versionSignatureRepository;
  @Mock PendingSignatureService pendingSignatureService;
  @Mock PendingSignatureRepository pendingSignatureRepository;

  ArtifactQueryService artifactQueryService;
  ArtifactDeploymentService artifactService;

  @BeforeEach
  void buildTheServices() {
    this.artifactQueryService =
        new ArtifactQueryService(
            this.artifactRepository,
            this.artifactVersionRepository,
            this.artifactConverter,
            this.storageStrategyRegistry);
    final var signatureService =
        new ArtifactSignatureService(
            this.versionSignatureRepository,
            this.artifactVersionRepository,
            this.storageStrategyRegistry,
            this.keyStoreService,
            this.pgpVerifierService,
            this.artifactQueryService,
            this.pendingSignatureService);
    final var pluginMetadataService =
        new MavenPluginMetadataService(
            this.artifactRepository,
            this.artifactVersionRepository,
            this.artifactQueryService,
            this.storageStrategyRegistry);
    final var rowWriteService =
        new ArtifactRowWriteService(
            this.artifactRepository,
            this.artifactVersionRepository,
            this.versionDeveloperRepository,
            this.versionLicenseRepository,
            this.artifactUpsertHelper,
            this.artifactVersionWriteService,
            this.artifactQueryService,
            this.storageStrategyRegistry);
    this.artifactService =
        new ArtifactDeploymentService(
            this.artifactQueryService,
            signatureService,
            pluginMetadataService,
            new ArtifactDeploymentRulesService(
                this.artifactRepository, this.storageStrategyRegistry),
            new ArtifactPomRegistrationService(
                this.repoTxService,
                rowWriteService,
                this.pendingSignatureService,
                signatureService,
                pluginMetadataService),
            new ArtifactRowDeleteService(
                this.repoTxService,
                this.artifactRepository,
                this.artifactVersionRepository,
                this.pendingSignatureRepository,
                this.artifactVersionWriteService));
  }

  private Artifact stubArtifact(final String latest) {
    final var artifact = new Artifact();
    artifact.setId(UUID.randomUUID());
    artifact.setLatest(latest);

    when(this.artifactRepository.findByRepoIdAndGroupNameAndArtifactName(
            REPO_ID, "com.acme", "lib"))
        .thenReturn(Optional.of(artifact));

    return artifact;
  }

  @Test
  @DisplayName("versions: a plain sort is a database page mapped to the panel item")
  void versionsAreAPageOfTheDatabase() {
    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "lastUpdatedAt"));
    final var row = row("1.0");
    final var item = io.repsy.os.generated.model.ArtifactVersionListItem.builder().build();
    when(this.artifactVersionRepository.findAllByRepoIdAndGroupNameAndArtifactName(
            REPO_ID, "com.acme", "lib", pageable))
        .thenReturn(new PageImpl<>(List.of(row), pageable, 1));
    when(this.artifactConverter.toArtifactVersionListItemDto(row)).thenReturn(item);

    final var page =
        this.artifactQueryService.getArtifactVersions(REPO_ID, "com.acme", "lib", pageable);

    assertThat(page.getContent()).containsExactly(item);
    assertThat(page.getTotalElements()).isEqualTo(1);
  }

  @Test
  @DisplayName("versions containing a text: a plain sort is a database page with a LIKE-free name")
  void versionsContainingATextAreAPageOfTheDatabase() {
    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "lastUpdatedAt"));
    when(this.artifactVersionRepository
            .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
                REPO_ID, "com.acme", "lib", "1.0", pageable))
        .thenReturn(Page.empty());

    assertThat(
            this.artifactQueryService.getArtifactVersionsContainsVersion(
                REPO_ID, "com.acme", "lib", "1.0", pageable))
        .isEmpty();
  }

  @Test
  @DisplayName("versions containing a text, sorted by versionName: Maven order over the whole set")
  void versionsContainingATextAreSortedByMavenOrder() {
    when(this.artifactVersionRepository
            .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
                REPO_ID, "com.acme", "lib", "1.", Pageable.unpaged()))
        .thenReturn(new PageImpl<>(List.of(row("1.9.0"), row("1.10.0"), row("1.2.0"))));
    when(this.artifactConverter.toArtifactVersionListItemDto(any()))
        .thenAnswer(
            invocation -> {
              final ArtifactVersionListItem source = invocation.getArgument(0);
              return io.repsy.os.generated.model.ArtifactVersionListItem.builder()
                  .versionName(source.getVersionName())
                  .build();
            });

    final var page =
        this.artifactQueryService.getArtifactVersionsContainsVersion(
            REPO_ID,
            "com.acme",
            "lib",
            "1.",
            PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "versionName")));

    assertThat(page.getContent())
        .extracting(io.repsy.os.generated.model.ArtifactVersionListItem::getVersionName)
        .containsExactly("1.2.0", "1.9.0");
    assertThat(page.getTotalElements()).isEqualTo(3);
    verify(this.artifactVersionRepository, never())
        .findAllByRepoIdAndGroupNameAndArtifactNameContainsVersionName(
            any(), any(), any(), any(), argThat(Pageable::isPaged));
  }

  @Test
  @DisplayName("artifacts of a group name and of an artifact name are LIKE pages")
  void artifactSearchesUseLikePatterns() {
    final var pageable = PageRequest.of(1, 5);
    when(this.artifactRepository.findAllByRepoIdAndContainsGroupName(
            REPO_ID, LikePatterns.of("%", "acme", "%"), pageable))
        .thenReturn(Page.empty());
    when(this.artifactRepository.findAllByRepoIdContainsArtifactName(
            REPO_ID, "com.acme", LikePatterns.of("%", "li", "%"), pageable))
        .thenReturn(Page.empty());

    assertThat(this.artifactQueryService.getArtifactsContainsGroupName(REPO_ID, "acme", pageable))
        .isEmpty();
    assertThat(
            this.artifactQueryService.getArtifactsContainsArtifactName(
                REPO_ID, "com.acme", "li", pageable))
        .isEmpty();
  }

  @Test
  @DisplayName("the artifacts of a group are listed whole")
  void artifactsOfAGroup() {
    final var artifact = new Artifact();
    when(this.artifactRepository.findAllByRepoIdAndGroupName(REPO_ID, "com.acme"))
        .thenReturn(List.of(artifact));

    assertThat(this.artifactQueryService.getArtifacts(REPO_ID, "com.acme"))
        .containsExactly(artifact);
  }

  @Test
  @DisplayName("version names of an artifact; a missing artifact is artifactNotFound")
  void versionNamesOfAnArtifact() {
    final var artifact = this.stubArtifact("1.1");
    final var one = new ArtifactVersion();
    one.setVersionName("1.0");
    final var two = new ArtifactVersion();
    two.setVersionName("1.1");
    when(this.artifactVersionRepository.findByArtifactId(artifact.getId()))
        .thenReturn(List.of(one, two));

    assertThat(this.artifactQueryService.getArtifactVersionNames(REPO_ID, "com.acme", "lib"))
        .containsExactly("1.0", "1.1");
    assertThatThrownBy(
            () -> this.artifactQueryService.getArtifactVersionNames(REPO_ID, "com.acme", "ghost"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactNotFound");
  }

  @Test
  @DisplayName("only one: exactly one artifact in the group, exactly one version of the artifact")
  void onlyOne() {
    when(this.artifactRepository.countByRepoIdAndGroupName(REPO_ID, "one")).thenReturn(1L);
    when(this.artifactRepository.countByRepoIdAndGroupName(REPO_ID, "two")).thenReturn(2L);
    when(this.artifactRepository.countByRepoIdAndGroupName(REPO_ID, "none")).thenReturn(0L);
    when(this.artifactVersionRepository.countByRepoIdAndGroupNameAndArtifactName(
            REPO_ID, "com.acme", "one"))
        .thenReturn(1L);
    when(this.artifactVersionRepository.countByRepoIdAndGroupNameAndArtifactName(
            REPO_ID, "com.acme", "many"))
        .thenReturn(3L);

    assertThat(this.artifactQueryService.hasOnlyOneArtifact(REPO_ID, "one")).isTrue();
    assertThat(this.artifactQueryService.hasOnlyOneArtifact(REPO_ID, "two")).isFalse();
    assertThat(this.artifactQueryService.hasOnlyOneArtifact(REPO_ID, "none")).isFalse();
    assertThat(this.artifactQueryService.hasOnlyOneVersion(REPO_ID, "com.acme", "one")).isTrue();
    assertThat(this.artifactQueryService.hasOnlyOneVersion(REPO_ID, "com.acme", "many")).isFalse();
  }

  @Test
  @DisplayName("requireArtifactVersion tells a missing artifact from a missing version")
  void requireArtifactVersion() {
    final var artifact = this.stubArtifact("1.0");
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(new ArtifactVersion()));
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "9.9"))
        .thenReturn(Optional.empty());

    this.artifactQueryService.requireArtifactVersion(REPO_ID, "com.acme", "lib", "1.0");

    assertThatThrownBy(
            () ->
                this.artifactQueryService.requireArtifactVersion(REPO_ID, "com.acme", "lib", "9.9"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");
    assertThatThrownBy(
            () ->
                this.artifactQueryService.requireArtifactVersion(
                    REPO_ID, "com.acme", "ghost", "1.0"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactNotFound");
  }

  @Test
  @DisplayName(
      "the detail of a version: the named one, or the artifact's latest when none is named")
  void versionDetail() {
    final var artifact = this.stubArtifact("1.1");
    final var latest = new ArtifactVersion();
    final var named = new ArtifactVersion();
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.1"))
        .thenReturn(Optional.of(latest));
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "1.0"))
        .thenReturn(Optional.of(named));
    final var latestInfo = ArtifactVersionInfo.builder().versionName("1.1").build();
    final var namedInfo = ArtifactVersionInfo.builder().versionName("1.0").build();
    when(this.artifactConverter.toArtifactVersionInfo(artifact, latest)).thenReturn(latestInfo);
    when(this.artifactConverter.toArtifactVersionInfo(artifact, named)).thenReturn(namedInfo);

    assertThat(this.artifactQueryService.getArtifactVersion(REPO_ID, "com.acme", "lib", null))
        .isSameAs(latestInfo);
    assertThat(this.artifactQueryService.getArtifactVersion(REPO_ID, "com.acme", "lib", "1.0"))
        .isSameAs(namedInfo);
  }

  @Test
  @DisplayName("the detail of a missing version is artifactVersionNotFound")
  void versionDetailOfAMissingVersion() {
    final var artifact = this.stubArtifact("1.1");
    when(this.artifactVersionRepository.findByArtifactIdAndVersionName(artifact.getId(), "9.9"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> this.artifactQueryService.getArtifactVersion(REPO_ID, "com.acme", "lib", "9.9"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("artifactVersionNotFound");
  }

  @Test
  @DisplayName("a release's POM is named by its artifactId and version, other types have none")
  void pomFileNameOfAReleaseAndOfAPlugin() throws Exception {
    final var repoInfo = RepoInfo.builder().id(REPO_ID).storageKey(REPO_ID).name("mvn").build();

    assertThat(
            this.artifactQueryService.getArtifactVersionPomFilename(
                repoInfo, Path.of("/com/acme/lib"), RELEASE, "lib", "1.0"))
        .isEqualTo("lib-1.0.pom");
    assertThat(
            this.artifactQueryService.getArtifactVersionPomFilename(
                repoInfo, Path.of("/com/acme/lib"), PLUGIN, "lib", "1.0"))
        .isNull();
  }

  @Test
  @DisplayName("the unsigned path of a signature is the path without its .asc")
  void nonSignedStoragePath() {
    final var key = UUID.randomUUID();

    final var path =
        this.artifactService.getNonSignedStoragePath(
            StoragePath.of(key, "com/acme/lib/1.0/lib-1.0.jar.asc"));

    assertThat(path.getRelativePath().getPath()).isEqualTo("com/acme/lib/1.0/lib-1.0.jar");
    assertThat(path.getStorageKey()).isEqualTo(key);
  }

  @Test
  @DisplayName("the registered plugins and versions come from one query each, by the storage key")
  void registeredPluginsAndVersions() {
    final var storageKey = UUID.randomUUID();
    final var repoInfo = RepoInfo.builder().id(REPO_ID).storageKey(storageKey).name("mvn").build();
    when(this.artifactRepository.findRegisteredPlugins(eq(storageKey), eq("com.acme")))
        .thenReturn(List.of());
    when(this.artifactVersionRepository.findRegisteredVersions(storageKey, "com.acme", "lib"))
        .thenReturn(List.of());

    assertThat(this.artifactService.getRegisteredPlugins(repoInfo, "com.acme")).isEmpty();
    assertThat(this.artifactService.getRegisteredVersions(repoInfo, "com.acme", "lib")).isEmpty();
  }

  private static ArtifactVersionListItem row(final String versionName) {
    return new ArtifactVersionListItem() {
      @Override
      public String getVersionName() {
        return versionName;
      }

      @Override
      public LocalDateTime getLastUpdatedAt() {
        return LocalDateTime.of(2026, 1, 1, 0, 0);
      }

      @Override
      public boolean isSigned() {
        return false;
      }
    };
  }
}
