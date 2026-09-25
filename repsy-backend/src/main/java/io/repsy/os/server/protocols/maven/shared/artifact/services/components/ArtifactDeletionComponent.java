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

import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.DeletedItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class ArtifactDeletionComponent {

  private final MavenStorageService mavenStorageService;
  private final ArtifactServiceImpl artifactService;
  private final ApplicationEventPublisher eventPublisher;

  public Pair<DeletedItem, BaseUsages> deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName)
      throws IOException, XmlPullParserException {

    // Confirms the requested version is real before any storage or DB mutation runs. Without this
    // check, a version name that does not exist on an artifact that has exactly one real version
    // reached hasOnlyOneVersion() as true and cascaded into deleteArtifact() (and potentially
    // deleteGroup()), deleting the whole artifact instead of answering 404 (RPS-1190).
    this.artifactService.requireArtifactVersion(
        repoInfo.getStorageKey(), groupName, artifactName, versionName);

    if (this.artifactService.hasOnlyOneVersion(repoInfo.getStorageKey(), groupName, artifactName)) {
      // deleteArtifact() (and whatever it delegates to, e.g. deleteGroup()) publishes
      // ArtifactVersionDeletedEvent for every version it removes, which at this point is only
      // this one — no separate publish needed here.
      return this.deleteArtifact(repoInfo, groupName, artifactName);
    }

    // The metadata is rewritten first: it is the one step that can refuse (a file that cannot be
    // parsed), and it does so before a file is moved, so a refused delete has changed nothing. An
    // artifact without a maven-metadata.xml (Ivy, sbt, a raw PUT) has nothing to rewrite
    // (RPS-1331).
    final var metadataUsages =
        this.mavenStorageService.deleteVersionFromMetadata(
            repoInfo, groupName, artifactName, versionName);

    final var artifactUsage =
        this.mavenStorageService.deleteArtifactVersion(
            repoInfo.getStorageKey(), groupName, artifactName, versionName);

    final var totalUsage = artifactUsage - metadataUsages.getDiskUsage();
    final var usages = BaseUsages.builder().diskUsage(totalUsage * -1L).build();

    this.artifactService.deleteArtifactVersion(repoInfo, groupName, artifactName, versionName);

    this.publishVersionDeleted(repoInfo, groupName, artifactName, versionName);

    return Pair.of(DeletedItem.VERSION, usages);
  }

  public Pair<DeletedItem, BaseUsages> deleteArtifact(
      final RepoInfo repoInfo, final String groupName, final String artifactName) {

    if (this.artifactService.hasOnlyOneArtifact(repoInfo.getStorageKey(), groupName)) {
      return this.deleteGroup(repoInfo, groupName);
    }

    final var versionNames =
        this.artifactService.getArtifactVersionNames(
            repoInfo.getStorageKey(), groupName, artifactName);

    final var usage =
        this.mavenStorageService.deleteArtifact(repoInfo.getStorageKey(), groupName, artifactName);

    final var usages = BaseUsages.builder().diskUsage(usage * -1L).build();

    this.artifactService.deleteArtifact(repoInfo.getStorageKey(), groupName, artifactName);

    this.publishVersionsDeleted(repoInfo, groupName, artifactName, versionNames);

    return Pair.of(DeletedItem.ARTIFACT, usages);
  }

  public Pair<DeletedItem, BaseUsages> deleteGroup(
      final RepoInfo repoInfo, final String groupName) {

    // No special case for a "root group" (the one whose name prefixes every other group): its own
    // artifacts are removed like any group's, and a nested group such as com.acme.sub next to
    // com.acme is never touched (RPS-1190, RPS-1349).
    final var artifacts = this.artifactService.getArtifacts(repoInfo.getStorageKey(), groupName);
    final var versionNamesByArtifact =
        this.collectVersionNamesByArtifact(repoInfo, groupName, artifacts);
    final var artifactNames = artifacts.stream().map(Artifact::getArtifactName).toList();

    // Storage only removes this group's own artifact directories and group-level metadata files,
    // never the whole group directory: a subdirectory belonging to a nested sibling group (e.g.
    // com.acme.sub next to com.acme) must survive (RPS-1190).
    final long usage =
        this.mavenStorageService.deleteGroup(repoInfo.getStorageKey(), groupName, artifactNames);

    final BaseUsages usages = BaseUsages.builder().diskUsage(usage * -1L).build();

    this.artifactService.deleteGroup(repoInfo.getStorageKey(), groupName);

    versionNamesByArtifact.forEach(
        (artifactName, versionNames) ->
            this.publishVersionsDeleted(repoInfo, groupName, artifactName, versionNames));

    return Pair.of(DeletedItem.GROUP, usages);
  }

  private Map<String, List<String>> collectVersionNamesByArtifact(
      final RepoInfo repoInfo, final String groupName, final List<Artifact> artifacts) {

    final var versionNamesByArtifact = new LinkedHashMap<String, List<String>>();

    for (final var artifact : artifacts) {
      versionNamesByArtifact.put(
          artifact.getArtifactName(),
          this.artifactService.getArtifactVersionNames(
              repoInfo.getStorageKey(), groupName, artifact.getArtifactName()));
    }

    return versionNamesByArtifact;
  }

  private void publishVersionDeleted(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName) {

    this.eventPublisher.publishEvent(
        new ArtifactVersionDeletedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            groupName + ":" + artifactName,
            versionName));
  }

  private void publishVersionsDeleted(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final List<String> versionNames) {

    for (final var versionName : versionNames) {
      this.publishVersionDeleted(repoInfo, groupName, artifactName, versionName);
    }
  }
}
