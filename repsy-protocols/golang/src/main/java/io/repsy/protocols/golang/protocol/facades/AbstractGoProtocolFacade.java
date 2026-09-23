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
package io.repsy.protocols.golang.protocol.facades;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.golang.shared.dto.GoVersionInfo;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.module.validators.GoModFileValidator;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.golang.shared.utils.GoModuleHashCalculator;
import io.repsy.protocols.golang.shared.utils.GoModuleZipReader;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@NullMarked
public abstract class AbstractGoProtocolFacade<I> implements GoProtocolFacade<I> {

  private static final String PATH_SEPARATOR = "/";
  private static final String LIST_SUFFIX = "/@v/list";
  private static final String LATEST_SUFFIX = "/@latest";
  private static final String INFO_EXTENSION = ".info";
  private static final String MOD_EXTENSION = ".mod";
  private static final String USAGES = "usages";
  private static final String CONTENT_SHA256_KEY = "contentSha256";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final GoStorageService<I> goStorageService;
  private final GoModuleService<I> goModuleService;
  private final long maxModuleZipBytes;

  /**
   * @param maxModuleZipBytes The largest module zip an upload may carry. The body is a raw request
   *     body, which no multipart limit applies to, so a larger one is refused with 413 instead of
   *     being held whole in memory or spooled to disk without bound (RPS-1119).
   */
  protected AbstractGoProtocolFacade(
      final GoStorageService<I> goStorageService,
      final GoModuleService<I> goModuleService,
      final long maxModuleZipBytes) {
    this.goStorageService = goStorageService;
    this.goModuleService = goModuleService;
    this.maxModuleZipBytes = maxModuleZipBytes;
  }

  @Override
  public Resource download(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<I>getRepoInfo(context);
    final var path = decodePath(ProtocolContextUtils.getRelativePath(context).getPath());

    if (path.endsWith(LIST_SUFFIX)) {
      return this.handleVersionList(repoInfo, path);
    }

    if (path.endsWith(LATEST_SUFFIX)) {
      return this.handleLatestVersion(repoInfo, path);
    }

    return this.getResourceWithLegacyFallback(repoInfo, path);
  }

  /**
   * Spools the module zip to a temporary file instead of holding it whole in memory (RPS-1119):
   * {@code go.mod} is read out of it, it is hashed, and it is copied to storage, each from its own
   * pass over the spooled file. The module path and version are validated against the URL before
   * any of the body is read (RPS-1072), so a request that was always going to be refused is refused
   * without spooling it.
   */
  @Override
  @SneakyThrows
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength) {

    final var repoInfo = ProtocolContextUtils.<I>getRepoInfo(context);
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var modulePath = GoVersionUtils.extractModulePath(path);

    if (modulePath == null) {
      throw new BadRequestException("invalidModulePath");
    }

    final var version = GoVersionUtils.extractVersionFromPath(path);

    final var decodedPath = GoVersionUtils.decodeModulePath(modulePath);

    rejectInvalidIdentifiers(decodedPath, version);

    // A client that declares an oversized body is refused before any of it is read.
    if (contentLength > this.maxModuleZipBytes) {
      throw new MaxUploadSizeExceededException(this.maxModuleZipBytes);
    }

    try (final var spool = SpooledUpload.spool(inputStream, this.maxModuleZipBytes)) {
      this.uploadSpooled(context, repoInfo, decodedPath, version, spool);
    } catch (final EntryTooLargeException e) {
      // The body was chunked or understated its length, and outgrew the limit while it was read.
      throw new MaxUploadSizeExceededException(this.maxModuleZipBytes, e);
    }
  }

  private void uploadSpooled(
      final ProtocolContext context,
      final BaseRepoInfo<I> repoInfo,
      final String decodedPath,
      final String version,
      final SpooledUpload spool)
      throws IOException {

    verifySha256(spool.sha256Hex(), (String) context.getContextMap().get(CONTENT_SHA256_KEY));

    final var modContent = GoModuleZipReader.extractGoMod(spool.openStream(), decodedPath, version);
    GoModFileValidator.validate(modContent, decodedPath);

    final var zipHash = GoModuleHashCalculator.hashZip(spool.openStream());

    // The DB key is the case-preserved decoded path; the on-disk storage key is its !-escaped,
    // all-lower-case form (RPS-1232), so storage never has to rely on a case-sensitive filesystem.
    final var escapedPath = GoVersionUtils.escapeModulePath(decodedPath);

    // The version row is written first and stays uncommitted while the files are written
    // (RPS-1124), so a failed or losing upload never leaves storage and the database disagreeing.
    final var usages =
        this.goModuleService.publishModule(
            repoInfo,
            decodedPath,
            version,
            GoVersionUtils.extractGoVersionFromMod(modContent),
            GoModuleHashCalculator.hashMod(modContent),
            zipHash,
            () -> this.storeFiles(repoInfo, escapedPath, version, modContent, spool));

    context.addProperty(ARTIFACT_NAME, decodedPath);
    context.addProperty(ARTIFACT_VERSION, version);
    context.addProperty(USAGES, usages);
  }

  /**
   * Writes the {@code .mod}, {@code .zip} and {@code .info} files of a new version. Go versions are
   * immutable, so the version is always new and whatever this leaves behind on failure is removed:
   * the row is rolled back with the failure, so a partly written version would be orphaned files.
   */
  private BaseUsages storeFiles(
      final BaseRepoInfo<I> repoInfo,
      final String escapedPath,
      final String version,
      final byte[] modContent,
      final SpooledUpload spool)
      throws IOException {

    try {
      final var modUsages = this.writeModFile(repoInfo, escapedPath, version, modContent);

      final var zipStoragePath =
          StoragePath.of(
              repoInfo.getStorageKey(),
              this.goStorageService.getModuleZipRelativePath(escapedPath, version));
      final BaseUsages zipUsages;
      try (final var zipStream = spool.openStream()) {
        zipUsages =
            this.goStorageService.writeInputStreamToPath(
                zipStoragePath, zipStream, repoInfo.getName());
      }

      final var infoUsages = this.writeInfoFile(repoInfo, escapedPath, version);

      return BaseUsages.ofDisk(
          modUsages.getDiskUsage() + zipUsages.getDiskUsage() + infoUsages.getDiskUsage());
    } catch (final IOException | RuntimeException e) {
      this.goStorageService.deleteVersionFiles(
          StoragePath.of(repoInfo.getStorageKey(), PATH_SEPARATOR + escapedPath + "/@v/" + version),
          repoInfo.getName());
      throw e;
    }
  }

  /**
   * The module path and version are taken from the URL and stored in varchar columns, so a longer
   * one is refused with a 400 that names it before the upload is read (RPS-1072). Neither can be
   * cut: they are what the module is fetched by. The path is measured as it is stored: decoded and
   * case-preserved (RPS-1232). The length check runs first so an over-long version keeps answering
   * {@code moduleVersionTooLong} rather than {@code invalidModuleVersion}: a version can be both
   * over-long and a syntactically valid semver string (a long pre-release), and the length is the
   * more specific fault. Only once the version is a plausible length is it checked against Go's own
   * semver grammar (RPS-1227), so a client cannot store an arbitrary string as an immutable
   * "version" that {@code @v/list}/{@code @latest} then have to make sense of.
   */
  private static void rejectInvalidIdentifiers(final String decodedPath, final String version) {
    if (decodedPath.length() > GoVersionUtils.MAX_MODULE_PATH_LENGTH) {
      throw new BadRequestException("modulePathTooLong");
    }
    if (version.length() > GoVersionUtils.MAX_VERSION_LENGTH) {
      throw new BadRequestException("moduleVersionTooLong");
    }
    if (!GoVersionUtils.isValidSemver(version)) {
      throw new BadRequestException("invalidModuleVersion");
    }
  }

  private Resource handleVersionList(final BaseRepoInfo<I> repoInfo, final String path) {
    final var versions = this.listVersions(repoInfo, path);
    if (!versions.isEmpty()) {
      return new ByteArrayResource(versions.getBytes(StandardCharsets.UTF_8));
    }

    // See getResourceWithLegacyFallback: a legacy module is only stored under its lower-case
    // spelling, so an empty listing under the real (mixed) case is retried once against it.
    final var lowerPath = lowerCaseModuleSegment(path);
    final var fallbackVersions =
        lowerPath.equals(path) ? versions : this.listVersions(repoInfo, lowerPath);
    return new ByteArrayResource(fallbackVersions.getBytes(StandardCharsets.UTF_8));
  }

  private String listVersions(final BaseRepoInfo<I> repoInfo, final String path) {
    final var atVPath = path.substring(0, path.length() - "list".length());
    final var atVStoragePath = StoragePath.of(repoInfo.getStorageKey(), atVPath);

    return this.goStorageService.listDirectory(atVStoragePath).stream()
        .filter(item -> !item.isDirectory() && item.getName().endsWith(INFO_EXTENSION))
        .map(item -> item.getName().substring(0, item.getName().length() - INFO_EXTENSION.length()))
        .sorted(GoVersionUtils.COMPARATOR)
        .collect(Collectors.joining("\n"));
  }

  private Resource handleLatestVersion(final BaseRepoInfo<I> repoInfo, final String path) {
    // path was canonicalized by decodePath(): the module segment is the !-escaped storage form.
    final var escapedModulePath = path.substring(1, path.length() - LATEST_SUFFIX.length());
    final var decodedModulePath = GoVersionUtils.decodeModulePath(escapedModulePath);
    final var latestVersion =
        this.goModuleService
            .findLatestPublishedVersion(repoInfo, decodedModulePath)
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    final var infoPath =
        PATH_SEPARATOR + escapedModulePath + "/@v/" + latestVersion + INFO_EXTENSION;
    return this.getResourceWithLegacyFallback(repoInfo, infoPath);
  }

  /**
   * Reads a storage resource whose path starts with a module segment, falling back to the module
   * segment's all-lower-case spelling when the primary path is not found (RPS-1232). Before this
   * ticket, every module path was lower-cased before it reached storage, so a module that was
   * published under a mixed-case URL is, on disk, only reachable under its lower-cased spelling.
   * Rather than moving those already-stored objects, a request for the real (mixed) case that finds
   * nothing at its own escaped path is retried once against the lower-cased one, so a legacy module
   * keeps resolving for a client that has always used its real case. A module path that is already
   * all-lower-case is unaffected: the fallback path equals the primary one and is skipped.
   */
  private Resource getResourceWithLegacyFallback(
      final BaseRepoInfo<I> repoInfo, final String path) {
    try {
      return this.goStorageService.getResource(
          repoInfo.getName(), StoragePath.of(repoInfo.getStorageKey(), path));
    } catch (final ItemNotFoundException e) {
      final var lowerPath = lowerCaseModuleSegment(path);
      if (lowerPath.equals(path)) {
        throw e;
      }
      return this.goStorageService.getResource(
          repoInfo.getName(), StoragePath.of(repoInfo.getStorageKey(), lowerPath));
    }
  }

  /** Lower-cases only the module-path segment of {@code path} (before "/@v/"), if any. */
  private static String lowerCaseModuleSegment(final String path) {
    final var atVIndex = path.indexOf("/@v/");
    if (atVIndex < 0) {
      return path;
    }
    final var escapedModulePath = path.substring(1, atVIndex);
    final var lowered = GoVersionUtils.decodeModulePath(escapedModulePath).toLowerCase(Locale.ROOT);
    return PATH_SEPARATOR + lowered + path.substring(atVIndex);
  }

  private BaseUsages writeModFile(
      final BaseRepoInfo<I> repoInfo,
      final String modulePath,
      final String version,
      final byte[] modContent) {

    final var modPath = PATH_SEPARATOR + modulePath + "/@v/" + version + MOD_EXTENSION;
    return this.goStorageService.writeInputStreamToPath(
        StoragePath.of(repoInfo.getStorageKey(), modPath),
        new ByteArrayInputStream(modContent),
        repoInfo.getName());
  }

  @SneakyThrows
  private BaseUsages writeInfoFile(
      final BaseRepoInfo<I> repoInfo, final String modulePath, final String version) {

    final var versionInfo = GoVersionInfo.builder().version(version).time(Instant.now()).build();
    final var infoJson = OBJECT_MAPPER.writeValueAsString(versionInfo);
    final var infoPath = PATH_SEPARATOR + modulePath + "/@v/" + version + INFO_EXTENSION;

    try (final var infoStream =
        new ByteArrayInputStream(infoJson.getBytes(StandardCharsets.UTF_8))) {
      return this.goStorageService.writeInputStreamToPath(
          StoragePath.of(repoInfo.getStorageKey(), infoPath), infoStream, repoInfo.getName());
    }
  }

  /**
   * Canonicalizes the module-path segment of a request path to its !-escaped storage form
   * (RPS-1232): decodes it (resolving any !-escape and passing raw characters through unchanged)
   * and then re-escapes it. For a well-formed request, whose module segment already came in
   * escaped, this is a no-op; it also normalizes a client that sent raw upper-case characters
   * instead of escaping them. The suffix ("/@v/...", "/@latest") is left untouched.
   */
  private static String decodePath(final String path) {
    if (path.endsWith(LATEST_SUFFIX)) {
      final var encoded = path.substring(1, path.length() - LATEST_SUFFIX.length());
      return "/" + canonicalizeModulePath(encoded) + LATEST_SUFFIX;
    }
    final var atVIndex = path.indexOf("/@v/");
    if (atVIndex < 0) {
      return path;
    }
    final var encoded = path.substring(1, atVIndex);
    return "/" + canonicalizeModulePath(encoded) + path.substring(atVIndex);
  }

  private static String canonicalizeModulePath(final String encoded) {
    return GoVersionUtils.escapeModulePath(GoVersionUtils.decodeModulePath(encoded));
  }

  private static void verifySha256(final String computedHex, final @Nullable String expected) {
    if (expected == null) {
      return;
    }
    if (!computedHex.equals(expected.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException("sha256Mismatch");
    }
  }
}
