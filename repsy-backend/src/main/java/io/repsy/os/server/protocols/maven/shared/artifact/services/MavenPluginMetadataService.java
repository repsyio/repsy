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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.protocols.maven.shared.artifact.dtos.PluginPrefixChange;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.utils.MavenGavUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.PluginDescriptorReader;
import io.repsy.protocols.maven.shared.utils.PomModelUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What Maven asks of a plugin's metadata (RPS-2064): the registered plugins of a group, the prefix
 * a plugin's POM registers with, and the correction of that prefix when the plugin's jar arrives
 * after the POM.
 */
@Slf4j
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MavenPluginMetadataService {

  private final ArtifactRepository artifactRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final ArtifactQueryService artifactQueryService;

  private final StorageStrategyRegistry storageStrategyRegistry;

  /** One indexed query, see {@link ArtifactRepository#findRegisteredPlugins}. */
  public List<RegisteredPlugin> getRegisteredPlugins(
      final BaseRepoInfo<UUID> repoInfo, final String groupId) {

    return this.artifactRepository.findRegisteredPlugins(repoInfo.getStorageKey(), groupId);
  }

  /**
   * The jar of a plugin version that arrived after the POM registered it (what {@code mvn deploy}
   * sends) names the plugin's real {@code goalPrefix}: it replaces the derived one the POM had to
   * register, on the version and, when that was the artifact's prefix, on the artifact, in the same
   * transaction (RPS-1589). One indexed lookup for a main jar; only a registered plugin's jar is
   * read, bounded by {@link PluginDescriptorReader}, and what it holds never fails the upload.
   *
   * <p>A jar that is stored while the POM is still being registered by a concurrent request may
   * find no version yet and leave the derived prefix; storing the jar or the POM again corrects it.
   *
   * @return the change, {@code null} when there is nothing to correct
   */
  @Transactional
  public @Nullable PluginPrefixChange refreshPluginPrefixFromJar(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath jarPath, final Resource jar) {

    final var version = this.findPluginVersionWithPrefix(repoInfo.getStorageKey(), jarPath);

    if (version == null) {
      return null;
    }

    final var artifact = version.getArtifact();
    final var goalPrefix = readGoalPrefix(jar, jarPath, artifact.getArtifactName());

    if (goalPrefix == null || goalPrefix.equals(version.getPrefix())) {
      return null;
    }

    return this.replacePluginPrefix(version, goalPrefix);
  }

  /** The registered version of a plugin that has a prefix, {@code null} for anything else. */
  private @Nullable ArtifactVersion findPluginVersionWithPrefix(
      final UUID repoId, final StoragePath jarPath) {

    final var gav = MavenGavUtils.convertPathToGav(jarPath.getRelativePath().getPath());

    if (gav == null) {
      return null;
    }

    final var artifact =
        this.artifactQueryService
            .findArtifact(repoId, gav.getGroupId(), gav.getArtifactId())
            .orElse(null);

    if (artifact == null || !artifact.isPlugin()) {
      return null;
    }

    final var version =
        this.artifactQueryService.findVersionByGav(artifact.getId(), gav).orElse(null);

    return version != null && version.getPrefix() != null ? version : null;
  }

  private PluginPrefixChange replacePluginPrefix(
      final ArtifactVersion version, final String goalPrefix) {

    final var registered = Objects.requireNonNull(version.getPrefix());
    final var artifact = version.getArtifact();

    version.setPrefix(goalPrefix);
    this.artifactVersionRepository.save(version);

    // The prefix of the artifact is the one of the version that registered last: it follows this
    // version only when it was this version's.
    if (registered.equals(artifact.getPrefix())) {
      artifact.setPrefix(goalPrefix);
      this.artifactRepository.save(artifact);
    }

    return new PluginPrefixChange(artifact.getArtifactName(), registered, goalPrefix);
  }

  private static @Nullable String readGoalPrefix(
      final Resource jar, final StoragePath jarPath, final String artifactId) {

    try (final var in = jar.getInputStream()) {
      return PluginDescriptorReader.goalPrefix(in, artifactId);
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "The goalPrefix of the plugin jar {} could not be read, the registered one is kept: {}",
          jarPath.getRelativePath().getPath(),
          e.toString());

      return null;
    }
  }

  /**
   * The prefix a plugin's POM registers with, {@code null} when the POM is not a plugin's or the
   * prefix does not fit its column. It is the {@code goalPrefix} of {@code
   * META-INF/maven/plugin.xml} in the plugin's jar when the jar of that exact version is stored
   * already (Gradle's {@code maven-publish}, sbt and Ivy send it before the POM, and a plugin that
   * sets its own {@code goalPrefix} is otherwise not found by it, RPS-1458), and the one derived
   * from the artifactId otherwise. Only a plugin's POM costs the read of one stored file. A jar
   * that arrives after the POM corrects the prefix when it is stored ({@link
   * #refreshPluginPrefixFromJar}, RPS-1589). Reading never fails the upload, whatever the jar is.
   */
  public @Nullable String resolvePluginPrefix(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath pomPath,
      final Gav gav,
      final @Nullable Model pomModel) {

    if (pomModel == null || !PomModelUtils.artifactIsPlugin(pomModel)) {
      return null;
    }

    final var pom = pomPath.getRelativePath().getPath();
    final var jarPath = pom.substring(0, pom.length() - ".pom".length()) + ".jar";

    try {
      final var jar =
          this.mavenStorage()
              .get(StoragePath.of(repoInfo.getStorageKey(), jarPath), repoInfo.getName());

      if (jar.isPresent()) {
        try (final var in = jar.get().getInputStream()) {
          final var goalPrefix = PluginDescriptorReader.goalPrefix(in, gav.getArtifactId());

          if (goalPrefix != null) {
            return goalPrefix;
          }
        }
      }
    } catch (final IOException | RuntimeException e) {
      log.warn(
          "The goalPrefix of the plugin jar {} could not be read, the derived one is used: {}",
          jarPath,
          e.toString());
    }

    return derivedPluginPrefix(pomModel);
  }

  /** The plugin prefix of the POM's artifactId, or {@code null} if it is longer than its column. */
  private static @Nullable String derivedPluginPrefix(final Model pomModel) {

    return MavenPublishLimits.dropIfTooLong(
        PomModelUtils.getPrefixFromArtifactId(pomModel.getArtifactId()),
        MavenPublishLimits.MAX_PREFIX_LENGTH);
  }

  private StorageStrategy mavenStorage() {
    return this.storageStrategyRegistry.get(RepoType.MAVEN);
  }
}
