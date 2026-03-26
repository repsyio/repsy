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
package io.repsy.os.server.protocols.maven.shared.artifact.services.components;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.DeletedItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class ArtifactDeletionComponent {

  private final MavenStorageService mavenStorageService;
  private final ArtifactServiceImpl artifactService;

  public Pair<DeletedItem, BaseUsages> deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName)
      throws IOException, XmlPullParserException {

    if (this.artifactService.hasOnlyOneVersion(repoInfo.getStorageKey(), groupName, artifactName)) {
      return this.deleteArtifact(repoInfo, groupName, artifactName);
    }

    final var artifactUsage =
        this.mavenStorageService.deleteArtifactVersion(
            repoInfo.getStorageKey(), groupName, artifactName, versionName);

    final var versioningUsagesPair =
        this.mavenStorageService.deleteVersionFromMetadata(
            repoInfo, groupName, artifactName, versionName);

    final var totalUsage = artifactUsage - versioningUsagesPair.getSecond().getDiskUsage();
    final var usages = BaseUsages.builder().diskUsage(totalUsage * -1L).build();

    this.artifactService.deleteArtifactVersion(
        repoInfo, groupName, artifactName, versionName, versioningUsagesPair.getFirst());

    return Pair.of(DeletedItem.VERSION, usages);
  }

  public Pair<DeletedItem, BaseUsages> deleteArtifact(
      final RepoInfo repoInfo, final String groupName, final String artifactName) {

    if (this.artifactService.hasOnlyOneArtifact(repoInfo.getStorageKey(), groupName)) {
      return this.deleteGroup(repoInfo, groupName);
    }

    final var usage =
        this.mavenStorageService.deleteArtifact(repoInfo.getStorageKey(), groupName, artifactName);

    final var usages = BaseUsages.builder().diskUsage(usage * -1L).build();

    this.artifactService.deleteArtifact(repoInfo.getStorageKey(), groupName, artifactName);

    return Pair.of(DeletedItem.ARTIFACT, usages);
  }

  public Pair<DeletedItem, BaseUsages> deleteGroup(
      final RepoInfo repoInfo, final String groupName) {

    final var groups = this.artifactService.getGroupNames(repoInfo.getStorageKey());

    final var rootGroupOpt = this.findRootGroup(groups);

    if (rootGroupOpt.isPresent() && rootGroupOpt.get().equals(groupName)) {
      final var rootGroup = rootGroupOpt.get();

      final var artifacts = this.artifactService.getArtifacts(repoInfo.getStorageKey(), rootGroup);

      return this.deleteArtifacts(repoInfo, rootGroup, artifacts);
    }

    final long usage = this.mavenStorageService.deleteGroup(repoInfo.getStorageKey(), groupName);

    final BaseUsages usages = BaseUsages.builder().diskUsage(usage * -1L).build();

    this.artifactService.deleteGroup(repoInfo.getStorageKey(), groupName);

    return Pair.of(DeletedItem.GROUP, usages);
  }

  // (io.repsy)  -> Root group
  // (io.repsy).multi_module_nosnapshot -> Sub group
  // (io.repsy).multi_module_snapshot -> Sub group
  // (io.repsy).library_nosnapshot -> Sub group
  private Optional<String> findRootGroup(final List<String> groups) {

    if (groups.isEmpty() || groups.size() == 1) {
      return Optional.empty();
    }

    final var shortestGroup =
        groups.stream().min(Comparator.comparingInt(String::length)).orElseThrow();

    final var isCommonPrefix = groups.stream().allMatch(group -> group.startsWith(shortestGroup));

    return isCommonPrefix ? Optional.of(shortestGroup) : Optional.empty();
  }

  private Pair<DeletedItem, BaseUsages> deleteArtifacts(
      final RepoInfo repoInfo, final String rootGroup, final List<Artifact> artifacts) {

    var totalUsage = 0L;

    for (final var artifact : artifacts) {
      totalUsage +=
          this.mavenStorageService.deleteArtifact(
              repoInfo.getStorageKey(), rootGroup, artifact.getArtifactName());

      this.artifactService.deleteArtifact(
          repoInfo.getStorageKey(), rootGroup, artifact.getArtifactName());
    }

    final var usages = BaseUsages.builder().diskUsage(totalUsage * -1L).build();

    return Pair.of(DeletedItem.GROUP, usages);
  }
}
