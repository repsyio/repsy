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
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.PluginPrefixChange;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredPlugin;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.artifact.services.contracts.AbstractArtifactService;
import io.repsy.protocols.maven.shared.utils.PomModelUtils;
import io.repsy.protocols.maven.shared.utils.SignatureFileUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deploy side of the Maven artifacts (RPS-2064): the bean the shared protocol module sees as
 * its {@code ArtifactService} contract, and the owner of the transaction of every operation of it.
 * The work is done by focused collaborators (RPS-2167), which have no transaction of their own and
 * run inside the one of the operation they are called from:
 *
 * <ul>
 *   <li>{@link ArtifactDeploymentRulesService}: the judgement of an upload (layout, version type,
 *       override);
 *   <li>{@link ArtifactPomRegistrationService} and {@link ArtifactRowWriteService}: the
 *       registration of a stored POM and its artifact and version rows;
 *   <li>{@link ArtifactRowDeleteService}: the deletes;
 *   <li>{@link ArtifactSignatureService}, {@link MavenPluginMetadataService} and {@link
 *       ArtifactQueryService}: the signature, plugin and registered-version operations.
 * </ul>
 */
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ArtifactDeploymentService extends AbstractArtifactService<UUID> {

  private final ArtifactQueryService artifactQueryService;
  private final ArtifactSignatureService artifactSignatureService;
  private final MavenPluginMetadataService mavenPluginMetadataService;
  private final ArtifactDeploymentRulesService artifactDeploymentRulesService;
  private final ArtifactPomRegistrationService artifactPomRegistrationService;
  private final ArtifactRowDeleteService artifactRowDeleteService;

  /** One indexed query, see {@link ArtifactQueryService#getRegisteredVersions}. */
  @Override
  public List<RegisteredVersion> getRegisteredVersions(
      final BaseRepoInfo<UUID> repoInfo, final String groupId, final String artifactId) {

    return this.artifactQueryService.getRegisteredVersions(repoInfo, groupId, artifactId);
  }

  /** One indexed query, see {@link MavenPluginMetadataService#getRegisteredPlugins}. */
  @Override
  public List<RegisteredPlugin> getRegisteredPlugins(
      final BaseRepoInfo<UUID> repoInfo, final String groupId) {

    return this.mavenPluginMetadataService.getRegisteredPlugins(repoInfo, groupId);
  }

  @Override
  public StoragePath getNonSignedStoragePath(final StoragePath signedStoragePath) {

    return this.artifactSignatureService.getNonSignedStoragePath(signedStoragePath);
  }

  /**
   * See {@link ArtifactSignatureService#verifySignature}. No transaction of its own, as there
   * (RPS-2173): the class-level read-only one would hold a pooled connection across the parking.
   */
  @Override
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public SignatureOutcome verifySignature(
      final BaseRepoInfo<UUID> repoInfo,
      final StoragePath signedStoragePath,
      final Resource signature) {

    return this.artifactSignatureService.verifySignature(repoInfo, signedStoragePath, signature);
  }

  /** See {@link MavenPluginMetadataService#refreshPluginPrefixFromJar}. */
  @Override
  @Transactional
  public @Nullable PluginPrefixChange refreshPluginPrefixFromJar(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath jarPath, final Resource jar) {

    return this.mavenPluginMetadataService.refreshPluginPrefixFromJar(repoInfo, jarPath, jar);
  }

  /**
   * Classifies an upload of a file that sits in the Maven layout and refuses a path that is not
   * one, see {@link ArtifactDeploymentRulesService#getVersionType}.
   *
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException {@code invalidArtifactPath}
   *     if the path does not parse to a GAV
   */
  @Override
  public ArtifactVersionType getVersionType(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath) {

    return this.artifactDeploymentRulesService.getVersionType(repoInfo, storagePath);
  }

  /** See {@link ArtifactDeploymentRulesService#getVersionTypeByMetadataTypeFiles}. */
  @Override
  public @Nullable ArtifactVersionType getVersionTypeByMetadataTypeFiles(
      final BaseRepoInfo<UUID> repoInfo, final byte[] content, final StoragePath storagePath) {

    return this.artifactDeploymentRulesService.getVersionTypeByMetadataTypeFiles(
        repoInfo, content, storagePath);
  }

  /**
   * Refuses an upload that the repo settings do not allow, see {@link
   * ArtifactDeploymentRulesService#checkDeploymentRules}.
   */
  @Override
  public void checkDeploymentRules(
      final BaseRepoInfo<UUID> repoInfo,
      final @Nullable ArtifactVersionType versionType,
      final StoragePath storagePath) {

    this.artifactDeploymentRulesService.checkDeploymentRules(repoInfo, versionType, storagePath);
  }

  /**
   * Registers a stored POM, or records a verified signature and updates whether the version is
   * signed. Neither is decided from the whole path any more: a file is told to be a POM (or the
   * signature of one) by its file name's {@code .pom} (or {@code .pom.asc}) suffix alone, the same
   * rule {@link PomModelUtils#isPomToParse} and {@link SignatureFileUtils#isPomSignature} apply
   * before the file is stored. Before, an artifactId or directory that merely contained {@code
   * .pom} (for example {@code bar.pom.utils}) made every one of its files, checksums and signatures
   * look like a POM or a POM signature, so a jar answered {@code malformedPomFile} and a stored
   * {@code maven-metadata.xml} failed the same way right after being written (RPS-1196).
   *
   * <p>On a repo that verifies every signature (RPS-1188) a stored artifact {@code .asc} is a
   * verified signature too, and a stored signable file (a POM included, once it is registered)
   * loses the verified signature of its previous bytes, so {@code signed} is recomputed after every
   * upload into a registered version. On any other repo nothing of that is done: no query is made
   * for a file that registers nothing.
   */
  @Override
  @Transactional
  public void createOrUpdateArtifact(
      final BaseRepoInfo<UUID> repoInfo, final StoragePath storagePath, final Resource resource) {

    // Cannot create artifact for signed files. The signature itself was verified before it was
    // stored (see verifySignature), so here it only records that and marks the version signed. It
    // is looked up by the repo's storage key, so it does not need the repo row.
    if (SignatureFileUtils.isSignatureToVerify(
        storagePath, repoInfo.isPgpVerifyAllSignaturesEnabled())) {
      this.artifactSignatureService.processSignedFile(repoInfo, storagePath);
      return;
    }

    // A jar, a classifier file, a checksum or a metadata file registers nothing. Nothing below is
    // loaded for them, so a normal `mvn deploy` does not pay a repo query per file (RPS-1179). A
    // repo that verifies every signature does look at a signable one, see refreshSignedForFile.
    if (!PomModelUtils.isPomToParse(storagePath)) {
      this.artifactSignatureService.refreshSignedForFile(repoInfo, storagePath);
      return;
    }

    this.artifactPomRegistrationService.register(repoInfo, storagePath, resource);
  }

  @Transactional
  public void deleteArtifact(final UUID repoId, final String groupName, final String artifactName) {

    this.artifactRowDeleteService.deleteArtifact(repoId, groupName, artifactName);
  }

  @Transactional
  public void deleteArtifactVersion(
      final RepoInfo repoInfo,
      final String groupName,
      final String artifactName,
      final String versionName) {

    this.artifactRowDeleteService.deleteArtifactVersion(
        repoInfo, groupName, artifactName, versionName);
  }

  @Transactional
  public void deleteGroup(final UUID repoId, final String groupName) {

    this.artifactRowDeleteService.deleteGroup(repoId, groupName);
  }
}
