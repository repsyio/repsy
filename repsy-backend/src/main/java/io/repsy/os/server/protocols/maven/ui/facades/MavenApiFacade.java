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
package io.repsy.os.server.protocols.maven.ui.facades;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacadeMavenAdapter;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoSettingsForm;
import io.repsy.os.shared.repo.dtos.RepoSettingsInfo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.repo.utils.RepoUtils;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MavenApiFacade implements ProtocolApiFacadeMavenAdapter {

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull ArtifactServiceImpl artifactService;
  private final @NonNull MavenStorageService mavenStorageService;

  @Transactional
  public BaseUsages deleteRepo(final @NonNull RepoInfo repoInfo) {

    final var free = this.mavenStorageService.deleteRepo(repoInfo.getStorageKey());

    return BaseUsages.ofDisk(-1 * free);
  }

  public @NonNull List<StorageItemInfo> getItems(
      final @NonNull RepoInfo repoInfo, final @NonNull RelativePath relativePath) {

    this.validateParams(repoInfo.getName(), relativePath.getPath());

    final var info = this.repoTxService.getRepo(repoInfo.getStorageKey());

    final var storagePath = new StoragePath(info.getStorageKey(), relativePath);

    return this.mavenStorageService.getItems(storagePath);
  }

  public @NonNull RepoSettingsInfo getSettings(final @NonNull String repoName) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    return RepoSettingsInfo.builder()
        .privateRepo(repoInfo.isPrivateRepo())
        .searchable(repoInfo.isSearchable())
        .releases(repoInfo.getReleases())
        .snapshots(repoInfo.getSnapshots())
        .allowOverride(repoInfo.isAllowOverride())
        .build();
  }

  @Transactional
  public void updateSettings(
      final @NonNull RepoInfo repoInfo, final @NonNull RepoSettingsForm settings) {

    this.repoTxService.updateSettings(repoInfo.getStorageKey(), settings);
  }

  private void validateParams(final @NonNull String repoName, final @NonNull String path) {

    RepoUtils.validateRepoName(repoName);

    final var segments = path.split("/", -1);

    for (final var segment : segments) {
      if (segment.equals("..")) {
        throw new AccessNotAllowedException("invalidRequest");
      }
    }
  }

  public @NonNull ArtifactVersionInfo findArtifactVersion(
      final @NonNull RepoInfo repoInfo,
      final @NonNull String groupName,
      final @NonNull String artifactName,
      final @Nullable String versionName)
      throws IOException, XmlPullParserException {

    final var artifactVersionInfo =
        this.artifactService.getArtifactVersion(
            repoInfo.getStorageKey(), groupName, artifactName, versionName);

    final var artifactBasePath =
        this.mavenStorageService.getPath(
            "/" + artifactVersionInfo.getArtifactGroupName(),
            artifactVersionInfo.getArtifactName());

    final var pomFileName =
        this.artifactService.getArtifactVersionPomFilename(
            repoInfo,
            artifactBasePath,
            artifactVersionInfo.getType(),
            artifactVersionInfo.getArtifactName(),
            artifactVersionInfo.getVersionName());

    if (pomFileName != null) {
      final var filePath =
          artifactBasePath.resolve(artifactVersionInfo.getVersionName() + "/" + pomFileName);

      final var storagePath = StoragePath.of(repoInfo.getStorageKey(), filePath.toString());

      final var resource = this.mavenStorageService.getResource(storagePath, repoInfo.getName());

      final var fileContent = resource.getContentAsString(StandardCharsets.UTF_8);

      artifactVersionInfo.setPomFile(fileContent);
    }

    return artifactVersionInfo;
  }

  @Transactional
  public void createRepo(final @NonNull UUID repoId) {
    this.mavenStorageService.createRepo(repoId);
  }
}
