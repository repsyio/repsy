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
import io.repsy.protocols.golang.shared.exceptions.GoVersionGoneException;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.module.validators.GoModFileValidator;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.golang.shared.utils.GoModuleHashCalculator;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@NullMarked
public abstract class AbstractGoProtocolFacade<I> implements GoProtocolFacade<I> {

  private static final String PATH_SEPARATOR = "/";
  private static final String LIST_SUFFIX = "/@v/list";
  private static final String LATEST_SUFFIX = "/@latest";
  private static final String INFO_EXTENSION = ".info";
  private static final String MOD_EXTENSION = ".mod";
  private static final String ZIP_EXTENSION = ".zip";
  private static final Set<String> VERSIONED_EXTENSIONS =
      Set.of(INFO_EXTENSION, MOD_EXTENSION, ZIP_EXTENSION);

  private static final String USAGES = "usages";
  private static final String CONTENT_SHA256_KEY = "contentSha256";

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final GoStorageService<I> goStorageService;
  private final GoModuleService<I> goModuleService;

  protected AbstractGoProtocolFacade(
      final GoStorageService<I> goStorageService, final GoModuleService<I> goModuleService) {
    this.goStorageService = goStorageService;
    this.goModuleService = goModuleService;
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

    this.checkVersionNotDeleted(repoInfo, path);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), path);
    return this.goStorageService.getResource(repoInfo.getName(), storagePath);
  }

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
    // decodedPath preserves original casing for zip-entry lookup (e.g.
    // "github.com/BurntSushi/toml")
    final var decodedPath = GoVersionUtils.decodeModulePath(modulePath);
    // normalizedPath is lowercased for storage and DB (canonical form)
    final var normalizedPath = decodedPath.toLowerCase(Locale.ROOT);
    final var content = inputStream.readAllBytes();

    verifySha256(content, (String) context.getContextMap().get(CONTENT_SHA256_KEY));

    final var modContent = extractGoMod(content, decodedPath, version);
    GoModFileValidator.validate(modContent);

    this.goModuleService.publishModule(
        repoInfo,
        normalizedPath,
        version,
        GoVersionUtils.extractGoVersionFromMod(modContent),
        GoModuleHashCalculator.hashMod(modContent),
        GoModuleHashCalculator.hashZip(content));

    final var modUsages = this.writeModFile(repoInfo, normalizedPath, version, modContent);

    final var zipStoragePath =
        StoragePath.of(
            repoInfo.getStorageKey(),
            PATH_SEPARATOR + normalizedPath + "/@v/" + version + ZIP_EXTENSION);
    final var zipUsages =
        this.goStorageService.writeInputStreamToPath(
            zipStoragePath, new ByteArrayInputStream(content), repoInfo.getName());

    final var infoUsages = this.writeInfoFile(repoInfo, normalizedPath, version);

    final var totalDiskUsage =
        modUsages.getDiskUsage() + zipUsages.getDiskUsage() + infoUsages.getDiskUsage();
    context.addProperty(USAGES, BaseUsages.ofDisk(totalDiskUsage));
  }

  // Throws GoVersionGoneException if the requested versioned artifact (.info/.mod/.zip) has been
  // soft-deleted, so the caller returns 410 Gone per the GOPROXY spec.
  private void checkVersionNotDeleted(final BaseRepoInfo<I> repoInfo, final String path) {
    if (!path.contains("/@v/")) {
      return;
    }
    final var fileNameWithExt = path.substring(path.lastIndexOf('/') + 1);
    final var ext = fileNameWithExt.substring(fileNameWithExt.lastIndexOf('.'));
    if (!VERSIONED_EXTENSIONS.contains(ext)) {
      return;
    }
    final var modulePath = GoVersionUtils.extractModulePath(path);
    final var version = fileNameWithExt.substring(0, fileNameWithExt.lastIndexOf('.'));
    if (modulePath != null
        && this.goModuleService.isVersionDeleted(repoInfo, modulePath, version)) {
      throw new GoVersionGoneException(version);
    }
  }

  private Resource handleVersionList(final BaseRepoInfo<I> repoInfo, final String path) {
    final var atVPath = path.substring(0, path.length() - "list".length());
    final var atVStoragePath = StoragePath.of(repoInfo.getStorageKey(), atVPath);

    final var versions =
        this.goStorageService.listDirectory(atVStoragePath).stream()
            .filter(item -> !item.isDirectory() && item.getName().endsWith(INFO_EXTENSION))
            .map(
                item ->
                    item.getName().substring(0, item.getName().length() - INFO_EXTENSION.length()))
            .sorted(GoVersionUtils.COMPARATOR)
            .collect(Collectors.joining("\n"));

    return new ByteArrayResource(versions.getBytes(StandardCharsets.UTF_8));
  }

  private Resource handleLatestVersion(final BaseRepoInfo<I> repoInfo, final String path) {
    final var modulePath = path.substring(1, path.length() - LATEST_SUFFIX.length());
    final var latestVersion =
        this.goModuleService
            .findLatestPublishedVersion(repoInfo, modulePath)
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    final var infoPath = PATH_SEPARATOR + modulePath + "/@v/" + latestVersion + INFO_EXTENSION;
    return this.goStorageService.getResource(
        repoInfo.getName(), StoragePath.of(repoInfo.getStorageKey(), infoPath));
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

  @SneakyThrows
  private static byte[] extractGoMod(
      final byte[] zipContent, final String modulePath, final String version) {

    final var entryName = modulePath + "@" + version + "/go.mod";
    try (final var zis = new ZipInputStream(new ByteArrayInputStream(zipContent))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entryName.equals(entry.getName())) {
          return zis.readAllBytes();
        }
        zis.closeEntry();
      }
    }
    throw new BadRequestException("goModNotFoundInZip");
  }

  private static String decodePath(final String path) {
    if (path.endsWith(LATEST_SUFFIX)) {
      final var encoded = path.substring(1, path.length() - LATEST_SUFFIX.length());
      return "/"
          + GoVersionUtils.decodeModulePath(encoded).toLowerCase(Locale.ROOT)
          + LATEST_SUFFIX;
    }
    final var atVIndex = path.indexOf("/@v/");
    if (atVIndex < 0) {
      return path;
    }
    final var encoded = path.substring(1, atVIndex);
    return "/"
        + GoVersionUtils.decodeModulePath(encoded).toLowerCase(Locale.ROOT)
        + path.substring(atVIndex);
  }

  @SneakyThrows
  private static void verifySha256(final byte[] content, final @Nullable String expected) {
    if (expected == null) {
      return;
    }
    final var computed = GoModuleHashCalculator.computeSha256Hex(content);
    if (!computed.equals(expected.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException("sha256Mismatch");
    }
  }
}
