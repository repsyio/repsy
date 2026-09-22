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
package io.repsy.protocols.maven.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenProtocolFacade<ID> implements MavenProtocolFacade<ID> {

  private static final String USAGES = "usages";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  private static final Set<String> SCANNABLE_EXTENSIONS = Set.of("jar", "war", "ear");

  private final MavenStorageService<ID> mavenStorageService;
  private final ArtifactService<ID> artifactService;

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.mavenStorageService.getResource(repoInfo.getName(), storagePath);
  }

  /**
   * Stores a file and registers it. A path outside the Maven layout is refused inside {@code
   * getDeployAndVersionType}, before {@code checkDeploymentRules} and {@code store}, so nothing is
   * written and the {@code usages} context property is never set (the usage post-processor reads it
   * only when present). A POM is parsed, and refused if its groupId is not the one of its path,
   * before it is stored. A POM signature ({@code .pom.asc}) is verified against the stored POM
   * before it is stored, so a refused one never reaches the repo and takes nothing else with it: an
   * existing version, its previous signature and its {@code signed} flag are left as they were. A
   * checksum is judged by the file it belongs to, so it is refused, and nothing is stored, when
   * that file would be (RPS-1183). A metadata signature ({@code maven-metadata.xml.asc}) is stored
   * unparsed and unverified, judged like a metadata checksum (RPS-1185).
   */
  @Override
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength)
      throws IOException, XmlPullParserException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    byte[] content = null;

    final var fileName = storagePath.getRelativePath().getFileName();
    final MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair;

    if (ArtifactUtils.isFileSuitableForGavExtraction(fileName)) {
      artifactPair = this.artifactService.getDeployAndVersionType(repoInfo, storagePath);
    } else {
      content = inputStream.readAllBytes();
      artifactPair =
          this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
              repoInfo, content, storagePath);
    }

    this.artifactService.checkDeploymentRules(repoInfo, artifactPair, storagePath);

    if (content == null && ArtifactUtils.isPomSignature(storagePath)) {
      // A signature is well below 1 KB, and it is read once here to be verified and then stored.
      content = inputStream.readAllBytes();
      this.artifactService.verifySignature(repoInfo, storagePath, new ByteArrayResource(content));
    }

    final var afterUploadUsage = this.store(repoInfo.getName(), storagePath, inputStream, content);

    final var resource = this.mavenStorageService.getResource(repoInfo.getName(), storagePath);

    this.artifactService.createOrUpdateArtifact(repoInfo, storagePath, resource);

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null && isScannableArtifact(gav, fileName)) {
      context.addProperty(ARTIFACT_NAME, gav.getGroupId() + ":" + gav.getArtifactId());
      context.addProperty(ARTIFACT_VERSION, resolveLogicalVersion(gav));
    }

    context.addProperty(USAGES, afterUploadUsage);
  }

  /** Stores what the client sent, from the buffer if the facade already had to read it whole. */
  private BaseUsages store(
      final String repoName,
      final StoragePath storagePath,
      final InputStream inputStream,
      final byte @Nullable [] content)
      throws IOException {

    if (content != null) {
      return this.mavenStorageService.writeInputStreamToPath(
          storagePath, new ByteArrayInputStream(content), repoName);
    }

    if (ArtifactUtils.isPomToParse(storagePath)) {
      return this.writeValidatedPom(repoName, storagePath, inputStream);
    }

    return this.mavenStorageService.writeInputStreamToPath(storagePath, inputStream, repoName);
  }

  /**
   * Parses the POM before anything is stored. {@code createOrUpdateArtifact} reads it back from
   * storage, so a malformed POM used to be written first and stay in the repo (and off the usage
   * counter) when the parse then failed. A POM is spooled to a temporary file, parsed, and only
   * then copied to storage, so a rejected one never reaches it.
   *
   * <p>The same holds for a POM whose declared groupId (its own, else its parent's) is not the
   * group of its path: it would be stored and served but never registered, so it is refused with
   * {@code pomGroupIdMismatch} before it is stored (RPS-1193).
   */
  private BaseUsages writeValidatedPom(
      final String repoName, final StoragePath storagePath, final InputStream inputStream)
      throws IOException {

    try (final var pom = SpooledUpload.spool(inputStream)) {
      final @Nullable Model model;

      try (final var pomStream = pom.openStream()) {
        model = ArtifactUtils.readModel(pomStream);
      }

      ArtifactUtils.checkPomGroupIdMatchesPath(model, storagePath.getRelativePath().getPath());

      try (final var pomStream = pom.openStream()) {
        return this.mavenStorageService.writeInputStreamToPath(storagePath, pomStream, repoName);
      }
    }
  }

  private static String resolveLogicalVersion(final Gav gav) {
    return gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();
  }

  private static boolean isScannableArtifact(final Gav gav, final String fileName) {
    final var extension = gav.getExtension();

    return extension != null
        && !ArtifactUtils.isChecksumFile(fileName)
        && gav.getClassifier() == null
        && SCANNABLE_EXTENSIONS.contains(extension);
  }
}
