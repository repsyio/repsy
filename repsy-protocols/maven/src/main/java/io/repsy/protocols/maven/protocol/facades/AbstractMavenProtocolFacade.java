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
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.maven.protocol.resources.SynthesizedFileResource;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.RegisteredVersion;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactMetadataSynthesizer;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.maven.shared.utils.MavenPublishLimits;
import io.repsy.protocols.maven.shared.utils.MavenUploadLimits;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Model;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@Slf4j
@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenProtocolFacade<ID> implements MavenProtocolFacade<ID> {

  private static final String USAGES = "usages";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  private static final Set<String> SCANNABLE_EXTENSIONS = Set.of("jar", "war", "ear");

  private final MavenStorageService<ID> mavenStorageService;
  private final ArtifactService<ID> artifactService;

  /**
   * Answers a path with what is stored there. Only the artifact-level {@code maven-metadata.xml}
   * (and its {@code .md5}, {@code .sha1}, {@code .sha256} and {@code .sha512} checksums) that is
   * not stored is answered from the registered versions instead of with a 404, so an artifact that
   * its client published without one (sbt, Ivy) can still be resolved by a dynamic version
   * (RPS-1369). The group-level file of the same names is answered from the registered plugins, so
   * that {@code mvn prefix:goal} finds a plugin that Gradle, sbt or Ivy published (RPS-1438). The
   * two have the same path shape, so the artifact-level answer is tried first and the group-level
   * one only when no artifact of that group and name is registered. Nothing is written. A stored
   * file always wins, and a checksum is generated only when the file it belongs to is not stored
   * either: a client that stored its own metadata is never answered the digest of a file it was not
   * served. A signature ({@code .asc}) and the version-level metadata of a {@code SNAPSHOT} are not
   * generated, and an artifact or group that is not registered stays a 404.
   *
   * <p>A stored file wins even when it lacks something registered later: the upload of a POM keeps
   * the stored artifact-level file complete with the versions (RPS-1437) and the stored group-level
   * file complete with the plugins (RPS-1457).
   */
  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    try {
      return this.mavenStorageService.getResource(repoInfo.getName(), storagePath);
    } catch (final ItemNotFoundException e) {
      var synthesized = this.synthesizeArtifactMetadata(repoInfo, relativePath.getPath());

      if (synthesized == null) {
        synthesized = this.synthesizeGroupMetadata(repoInfo, relativePath.getPath());
      }

      if (synthesized == null) {
        throw e;
      }

      return synthesized;
    }
  }

  private @Nullable Resource synthesizeArtifactMetadata(
      final BaseRepoInfo<ID> repoInfo, final String path) {

    final var request = ArtifactMetadataSynthesizer.parse(path);

    if (request == null
        || this.isChecksumOfStoredFile(
            repoInfo, request.checksumAlgorithm(), request.metadataPath())) {
      return null;
    }

    final var versions =
        this.artifactService.getRegisteredVersions(
            repoInfo, request.groupId(), request.artifactId());

    if (versions.isEmpty()) {
      return null;
    }

    return synthesizedResource(
        ArtifactMetadataSynthesizer.metadataXml(request.groupId(), request.artifactId(), versions),
        request.checksumAlgorithm(),
        request.fileName());
  }

  private @Nullable Resource synthesizeGroupMetadata(
      final BaseRepoInfo<ID> repoInfo, final String path) {

    final var request = ArtifactMetadataSynthesizer.parseGroupLevel(path);

    if (request == null
        || this.isChecksumOfStoredFile(
            repoInfo, request.checksumAlgorithm(), request.metadataPath())) {
      return null;
    }

    final var plugins = this.artifactService.getRegisteredPlugins(repoInfo, request.groupId());

    if (plugins.isEmpty()) {
      return null;
    }

    return synthesizedResource(
        ArtifactMetadataSynthesizer.groupMetadataXml(plugins),
        request.checksumAlgorithm(),
        request.fileName());
  }

  /** A checksum is not generated when the file it belongs to is stored: it would not match it. */
  private boolean isChecksumOfStoredFile(
      final BaseRepoInfo<ID> repoInfo,
      final @Nullable String checksumAlgorithm,
      final String metadataPath) {

    return checksumAlgorithm != null
        && this.mavenStorageService.exists(
            StoragePath.of(repoInfo.getStorageKey(), metadataPath), repoInfo.getName());
  }

  private static Resource synthesizedResource(
      final byte[] xml, final @Nullable String checksumAlgorithm, final String fileName) {

    return new SynthesizedFileResource(
        checksumAlgorithm == null
            ? xml
            : ArtifactMetadataSynthesizer.checksum(xml, checksumAlgorithm),
        fileName);
  }

  /**
   * Stores a file and registers it. A path outside the Maven layout is refused inside {@code
   * getVersionType}, before {@code checkDeploymentRules} and {@code store}, so nothing is written
   * and the {@code usages} context property is never set (the usage post-processor reads it only
   * when present). A POM is parsed, and refused if its groupId is not the one of its path, before
   * it is stored. A POM signature ({@code .pom.asc}) is verified against the stored POM before it
   * is stored, so a refused one never reaches the repo and takes nothing else with it: an existing
   * version, its previous signature and its {@code signed} flag are left as they were. So is every
   * other artifact signature ({@code .jar.asc}, {@code -sources.jar.asc}, {@code .module.asc}, ...)
   * on a repo that verifies every signature (RPS-1188); on any other repo those are stored as sent.
   * A checksum is judged by the file it belongs to, so it is refused, and nothing is stored, when
   * that file would be (RPS-1183). A metadata signature ({@code maven-metadata.xml.asc}) is stored
   * unparsed and unverified, judged like a metadata checksum (RPS-1185). A POM, its signature and
   * its checksum are told by the file name, never by the directory (RPS-1196).
   *
   * <p>On a repo that verifies every signature a signature may reach the repo before the file it
   * signs, or before the POM that registers its version, which Maven's parallel upload makes
   * routine. It is then kept back unverified ({@link SignatureOutcome#PARKED}): the request answers
   * 200 and stores and charges nothing, and the signature is verified when the file arrives. That
   * arrival fails (422 {@code pendingSignatureNotVerified}, the new file taken back) when it does
   * not verify (RPS-1188).
   *
   * <p>A POM that registers a version also adds it to the stored artifact-level {@code
   * maven-metadata.xml} when the file lacks it and the usage of that rewrite is added to the POM's;
   * that step never fails the upload (RPS-1437, see {@code reconcileStoredMetadata}). The POM of a
   * plugin (packaging {@code maven-plugin}) also adds the plugins of its group that the stored
   * group-level file lacks to that file, with the same guarantees (RPS-1457, see {@code
   * reconcileStoredGroupMetadata}).
   *
   * <p>What is left to fail after the store is the registration itself (a repo or a signed version
   * deleted meanwhile, a database error). The usage is set on the context whether it succeeds or
   * not, and a POM or POM signature that was new is taken back out of the repo first when it fails
   * (RPS-1199, see {@code register}).
   *
   * <p>A metadata-family file and a signature that is verified are read fully into memory, and a
   * POM is spooled to a temporary file, before anything about them is parsed or stored; each is
   * capped by {@link MavenUploadLimits} and refused with a 400 naming the limit, before anything is
   * read, when the client declares a larger body, and while it is read otherwise (RPS-1121).
   *
   * <p>A body with no byte in it is refused with a 400 {@code mavenUploadBodyEmpty} before anything
   * else is looked at, whether it was declared empty ({@code Content-Length: 0}), sent chunked with
   * no chunk, or already consumed before it got here, so a publisher is never answered 200 for a
   * zero-byte file that every consumer then fails to verify (RPS-1443).
   */
  @Override
  public void upload(
      final ProtocolContext context, final InputStream requestBody, final long contentLength)
      throws IOException, XmlPullParserException {

    final var inputStream = nonEmpty(requestBody);
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

    if (isSignatureToVerify(content, repoInfo, storagePath)) {
      // A signature is well below 1 KB, and it is read once here to be verified and then stored.
      content = readBoundedSignature(inputStream, contentLength);

      if (this.artifactService.verifySignature(
              repoInfo, storagePath, new ByteArrayResource(content))
          == SignatureOutcome.PARKED) {
        // Kept back until the file it signs arrives (RPS-1188): nothing is stored or charged.
        return;
      }
    }

    final var isNewRegisteredFile = this.isNewRegisteredFile(repoInfo, storagePath);

    final var stored = this.store(repoInfo.getName(), storagePath, inputStream, content);

    this.register(context, repoInfo, storagePath, stored, isNewRegisteredFile);

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null && isScannableArtifact(gav, fileName)) {
      context.addProperty(ARTIFACT_NAME, gav.getGroupId() + ":" + gav.getArtifactId());
      context.addProperty(ARTIFACT_VERSION, resolveLogicalVersion(gav));
    }
  }

  /**
   * Answers the body with its first byte put back, refusing one that has none (RPS-1443). The
   * artifact is what the body is, so an empty one can only be a mistake of the client or a body
   * that was read by something else on the way.
   */
  private static InputStream nonEmpty(final InputStream inputStream) throws IOException {

    final var body = new PushbackInputStream(inputStream, 1);
    final var first = body.read();

    if (first < 0) {
      throw new BadRequestException("mavenUploadBodyEmpty");
    }

    body.unread(first);

    return body;
  }

  /**
   * A POM, or a signature that is verified (the signature of one, or of any artifact file when the
   * repo verifies every signature), is the only file whose registration ({@code
   * createOrUpdateArtifact}) can still fail once it is stored, and the only one that is worth
   * taking back: it is told here, before the store, whether the file is a new one. A file that is
   * already there is a redeploy, and storing over it cannot be undone.
   *
   * <p>On a repo that verifies every signature that is also true of any signable file: a signature
   * that arrived before it and does not verify fails its upload (RPS-1188), and the new file is
   * taken back like a POM whose registration failed.
   */
  private boolean isNewRegisteredFile(
      final BaseRepoInfo<ID> repoInfo, final StoragePath storagePath) {

    final var verifyAll = repoInfo.isPgpVerifyAllSignaturesEnabled();

    return (ArtifactUtils.isPomToParse(storagePath)
            || ArtifactUtils.isSignatureToVerify(storagePath, verifyAll)
            || (verifyAll
                && ArtifactUtils.isSignableFile(storagePath.getRelativePath().getFileName())))
        && !this.mavenStorageService.exists(storagePath, repoInfo.getName());
  }

  /**
   * Registers a stored file and reports its usage, so what the repo holds and what it is charged
   * for cannot disagree (RPS-1199). Everything that can be told from the path, the POM and the
   * metadata is refused before the store, so this can only fail on the request's own data source:
   * the repo deleted meanwhile, the signed version deleted meanwhile, or a database error.
   *
   * <p>When it does, a file that was new is taken back out of the repo and not charged, so the
   * client that is answered with an error has stored nothing and can send it again, even where
   * {@code allowOverride} is off and a stored file cannot be replaced. A redeploy is left as it was
   * written (the previous content is gone once it is overwritten) and is charged, or the counter
   * would miss the bytes the repo holds. The usage post-processor also runs for a failed request,
   * so what is set on the context before the exception is rethrown is still settled.
   */
  private void register(
      final ProtocolContext context,
      final BaseRepoInfo<ID> repoInfo,
      final StoragePath storagePath,
      final Stored stored,
      final boolean isNewRegisteredFile) {

    final var usage = stored.usage();

    try {
      final var resource = this.mavenStorageService.getResource(repoInfo.getName(), storagePath);

      this.artifactService.createOrUpdateArtifact(repoInfo, storagePath, resource);
    } catch (final RuntimeException e) {
      if (!isNewRegisteredFile || !this.takeBack(repoInfo.getName(), storagePath, e)) {
        context.addProperty(USAGES, usage);
      }

      throw e;
    }

    if (ArtifactUtils.isPomToParse(storagePath)) {
      final var reconciled =
          this.reconcileStoredMetadata(repoInfo, storagePath)
              + (stored.plugin() ? this.reconcileStoredGroupMetadata(repoInfo, storagePath) : 0L);

      context.addProperty(USAGES, BaseUsages.ofDisk(usage.getDiskUsage() + reconciled));

      return;
    }

    context.addProperty(USAGES, usage);
  }

  /**
   * Adds the version a POM has just registered to the artifact-level {@code maven-metadata.xml}
   * that a client stored earlier, when that file does not list it (RPS-1437). Maven and Gradle
   * upload the file with each deploy, but Apache Ivy and sbt send none, so without this a later
   * publish of theirs stays hidden from {@code LATEST}, {@code RELEASE}, version ranges and {@code
   * 1.+} behind the versions the stored file lists. An artifact that has no stored file is answered
   * from the registered versions (RPS-1369) and is never asked for them here.
   *
   * <p>It is best effort. The version is registered and committed by now, so a failure here (a file
   * that cannot be parsed, a storage error, a database error) is logged and the upload is still
   * answered as a success: failing it would take the new POM back out of the repo while its row
   * stays, and the client's retry would be refused as a conflict. The stored file is only ever
   * changed by the rewrite that ends with it written, and a file that cannot be parsed is left
   * untouched.
   *
   * @return the change of the disk usage the rewrite caused, {@code 0} when there was none
   */
  private long reconcileStoredMetadata(
      final BaseRepoInfo<ID> repoInfo, final StoragePath storagePath) {

    return this.bestEffort(
        repoInfo,
        storagePath,
        "the version of",
        () -> {
          final var gav = ArtifactUtils.getGavByFile(storagePath);

          if (gav == null) {
            return 0L;
          }

          return this.mavenStorageService.addVersionsToMetadata(
              repoInfo,
              gav.getGroupId(),
              gav.getArtifactId(),
              () ->
                  this.artifactService
                      .getRegisteredVersions(repoInfo, gav.getGroupId(), gav.getArtifactId())
                      .stream()
                      .map(RegisteredVersion::versionName)
                      .toList());
        });
  }

  /**
   * Adds the plugins that the group-level {@code maven-metadata.xml} a client stored earlier does
   * not list to it, when the POM that has just registered is one of a plugin (RPS-1457). Maven
   * stores that file with each plugin it deploys, but Gradle, Apache Ivy, sbt and a raw PUT send
   * none, so without this the stored file keeps answering {@code mvn prefix:goal} for the plugins
   * Maven listed and the plugin of another client is not found. A group that has no stored file is
   * answered from the registered plugins (RPS-1438) and is never asked for them here. Every plugin
   * of the group the file lacks is added, not only this one, so a plugin that was registered while
   * the file was not there to be changed is added with the next one.
   *
   * <p>The plugin is added with the prefix the repository derives from its artifactId. A plugin
   * that sets its own {@code goalPrefix} and is published by {@code mvn deploy} into a group whose
   * file is stored already is added here first (Maven sends the POM before it reads the file it
   * merges its entry into), so that file ends up with its entry under the derived prefix too, next
   * to the one Maven merges in: harmless, {@code mvn prefix:goal} finds the plugin by either.
   *
   * <p>It is best effort in the way {@link #reconcileStoredMetadata} is.
   *
   * @return the change of the disk usage the rewrite caused, {@code 0} when there was none
   */
  private long reconcileStoredGroupMetadata(
      final BaseRepoInfo<ID> repoInfo, final StoragePath storagePath) {

    return this.bestEffort(
        repoInfo,
        storagePath,
        "the plugins of",
        () -> {
          final var gav = ArtifactUtils.getGavByFile(storagePath);

          if (gav == null) {
            return 0L;
          }

          return this.mavenStorageService.addPluginsToGroupMetadata(
              repoInfo,
              gav.getGroupId(),
              () -> this.artifactService.getRegisteredPlugins(repoInfo, gav.getGroupId()));
        });
  }

  /** A rewrite of a stored metadata file that is logged and counted as {@code 0} if it fails. */
  private long bestEffort(
      final BaseRepoInfo<ID> repoInfo,
      final StoragePath storagePath,
      final String what,
      final MetadataRewrite rewrite) {

    try {
      return rewrite.run();
    } catch (final RuntimeException | IOException e) {
      log.warn(
          "Adding {} {} to the stored maven-metadata.xml of repo {} failed, the file is left as it"
              + " is: {}",
          what,
          storagePath.getRelativePath().getPath(),
          repoInfo.getName(),
          e.toString());

      return 0L;
    }
  }

  @FunctionalInterface
  private interface MetadataRewrite {

    long run() throws IOException;
  }

  /** Removes a file this request has just stored, answering whether it is gone. */
  private boolean takeBack(
      final String repoName, final StoragePath storagePath, final RuntimeException cause) {

    try {
      this.mavenStorageService.deleteFile(storagePath);

      log.warn(
          "Registering {} in repo {} failed, so the file it stored was taken back: {}",
          storagePath.getRelativePath().getPath(),
          repoName,
          cause.toString());

      return true;
    } catch (final RuntimeException e) {
      log.error(
          "Registering {} in repo {} failed and the file it stored could not be taken back, it is"
              + " charged as stored",
          storagePath.getRelativePath().getPath(),
          repoName,
          e);

      return false;
    }
  }

  /**
   * What a store left behind: the usage of the file, and whether it is the POM of a plugin (only a
   * POM that is parsed before it is stored can be told to be one).
   */
  private record Stored(BaseUsages usage, boolean plugin) {}

  /** Stores what the client sent, from the buffer if the facade already had to read it whole. */
  private Stored store(
      final String repoName,
      final StoragePath storagePath,
      final InputStream inputStream,
      final byte @Nullable [] content)
      throws IOException {

    if (content != null) {
      return new Stored(
          this.mavenStorageService.writeInputStreamToPath(
              storagePath, new ByteArrayInputStream(content), repoName),
          false);
    }

    if (ArtifactUtils.isPomToParse(storagePath)) {
      return this.writeValidatedPom(repoName, storagePath, inputStream);
    }

    return new Stored(
        this.mavenStorageService.writeInputStreamToPath(storagePath, inputStream, repoName), false);
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
  private Stored writeValidatedPom(
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
        return new Stored(
            this.mavenStorageService.writeInputStreamToPath(storagePath, pomStream, repoName),
            ArtifactUtils.artifactIsPlugin(model));
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
   * Reads a signature that is verified ({@code .pom.asc}, or any artifact {@code .asc} when the
   * repo verifies every signature) whole, refusing one larger than {@link
   * MavenUploadLimits#MAX_SIGNATURE_BYTES} with a 400 before it is verified or stored (RPS-1121).
   */
  private static byte[] readBoundedSignature(
      final InputStream inputStream, final long contentLength) throws IOException {

    try {
      return BoundedEntryReader.readAllBytes(
          inputStream, contentLength, MavenUploadLimits.MAX_SIGNATURE_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("mavenSignatureTooLarge");
    }
  }

  /** A signature this repo verifies, that has not been read into memory as metadata already. */
  private static boolean isSignatureToVerify(
      final byte @Nullable [] content,
      final BaseRepoInfo<?> repoInfo,
      final StoragePath storagePath) {

    return content == null
        && ArtifactUtils.isSignatureToVerify(
            storagePath, repoInfo.isPgpVerifyAllSignaturesEnabled());
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
