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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.MavenUploadLimits;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
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
   * getVersionType}, before {@code checkDeploymentRules} and {@code store}, so nothing is written
   * and the {@code usages} context property is never set (the usage post-processor reads it only
   * when present). A POM is parsed, and refused if its groupId is not the one of its path, before
   * it is stored. A POM signature ({@code .pom.asc}) is verified against the stored POM before it
   * is stored, so a refused one never reaches the repo and takes nothing else with it: an existing
   * version, its previous signature and its {@code signed} flag are left as they were. A checksum
   * is judged by the file it belongs to, so it is refused, and nothing is stored, when that file
   * would be (RPS-1183). A metadata signature ({@code maven-metadata.xml.asc}) is stored unparsed
   * and unverified, judged like a metadata checksum (RPS-1185). A POM, its signature and its
   * checksum are told by the file name, never by the directory (RPS-1196).
   *
   * <p>A metadata-family file and a POM signature are read fully into memory, and a POM is spooled
   * to a temporary file, before anything about them is parsed or stored; each is capped by {@link
   * MavenUploadLimits} and refused with a 400 naming the limit, before anything is read, when the
   * client declares a larger body, and while it is read otherwise (RPS-1121).
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
    final @Nullable ArtifactVersionType versionType;

    if (ArtifactUtils.isFileSuitableForGavExtraction(fileName)) {
      versionType = this.artifactService.getVersionType(repoInfo, storagePath);
    } else {
      content = readBoundedMetadata(inputStream, contentLength);
      versionType =
          this.artifactService.getVersionTypeByMetadataTypeFiles(repoInfo, content, storagePath);
    }

    this.artifactService.checkDeploymentRules(repoInfo, versionType, storagePath);

    if (content == null && ArtifactUtils.isPomSignature(storagePath)) {
      // A signature is well below 1 KB, and it is read once here to be verified and then stored.
      content = readBoundedPomSignature(inputStream, contentLength);
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
   * counter) when the parse then failed. A POM is spooled to a temporary file, capped at {@link
   * MavenUploadLimits#MAX_POM_BYTES}, parsed, and only then copied to storage, so a rejected one
   * never reaches it and an oversized one is refused instead of filling the disk (RPS-1121).
   *
   * <p>The same holds for a POM whose declared groupId (its own, else its parent's) is not the
   * group of its path: it would be stored and served but never registered, so it is refused with
   * {@code pomGroupIdMismatch} before it is stored (RPS-1193), and so is one whose packaging is
   * longer than its column, with {@code pomPackagingTooLong} (RPS-1138).
   */
  private BaseUsages writeValidatedPom(
      final String repoName, final StoragePath storagePath, final InputStream inputStream)
      throws IOException {

    try (final var pom = SpooledUpload.spool(inputStream, MavenUploadLimits.MAX_POM_BYTES)) {
      final @Nullable Model model;

      try (final var pomStream = pom.openStream()) {
        model = ArtifactUtils.readModel(pomStream);
      }

      ArtifactUtils.checkPomGroupIdMatchesPath(model, storagePath.getRelativePath().getPath());
      MavenPublishLimits.checkPackaging(model);

      try (final var pomStream = pom.openStream()) {
        return this.mavenStorageService.writeInputStreamToPath(storagePath, pomStream, repoName);
      }
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("pomFileTooLarge");
    }
  }

  /**
   * Reads a metadata-family file ({@code maven-metadata.xml}, one of its checksums or its {@code
   * .asc}) whole, refusing one larger than {@link MavenUploadLimits#MAX_METADATA_BYTES} with a 400
   * before anything is stored (RPS-1121).
   */
  private static byte[] readBoundedMetadata(final InputStream inputStream, final long contentLength)
      throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(
          inputStream, contentLength, MavenUploadLimits.MAX_METADATA_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("mavenMetadataTooLarge");
    }
  }

  /**
   * Reads a POM signature ({@code .pom.asc}) whole, refusing one larger than {@link
   * MavenUploadLimits#MAX_POM_SIGNATURE_BYTES} with a 400 before it is verified or stored
   * (RPS-1121).
   */
  private static byte[] readBoundedPomSignature(
      final InputStream inputStream, final long contentLength) throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(
          inputStream, contentLength, MavenUploadLimits.MAX_POM_SIGNATURE_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("mavenSignatureTooLarge");
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
