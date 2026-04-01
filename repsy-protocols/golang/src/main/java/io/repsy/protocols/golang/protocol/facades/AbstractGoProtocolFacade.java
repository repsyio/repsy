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
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.golang.shared.dto.GoVersionInfo;
import io.repsy.protocols.golang.shared.module.services.GoModuleService;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.golang.shared.utils.GoVersionUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

@NullMarked
public abstract class AbstractGoProtocolFacade<ID> implements GoProtocolFacade<ID> {

  private static final String LIST_SUFFIX = "/@v/list";
  private static final String LATEST_SUFFIX = "/@latest";
  private static final String INFO_EXTENSION = ".info";
  private static final String ZIP_EXTENSION = ".zip";
  private static final String USAGES = "usages";

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final String MOD_EXTENSION = ".mod";

  private final GoStorageService<ID> goStorageService;
  private final GoModuleService<ID> goModuleService;

  protected AbstractGoProtocolFacade(
      final GoStorageService<ID> goStorageService, final GoModuleService<ID> goModuleService) {
    this.goStorageService = goStorageService;
    this.goModuleService = goModuleService;
  }

  @Override
  public Resource download(final ProtocolContext context) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();

    if (path.endsWith(LIST_SUFFIX)) {
      return this.handleVersionList(repoInfo, path);
    }

    if (path.endsWith(LATEST_SUFFIX)) {
      return this.handleLatestVersion(repoInfo, path);
    }

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), path);
    return this.goStorageService.getResource(repoInfo.getName(), storagePath);
  }

  @Override
  @SneakyThrows
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), path);

    if (path.endsWith(MOD_EXTENSION)) {
      final var content = inputStream.readAllBytes();
      final var usages =
          this.goStorageService.writeInputStreamToPath(
              storagePath, new ByteArrayInputStream(content), repoInfo.getName());

      final var modulePath = GoVersionUtils.extractModulePath(path);
      final var version = GoVersionUtils.extractVersionFromModPath(path);
      if (modulePath != null) {
        this.goModuleService.createOrUpdateModule(repoInfo, modulePath, version);
        final var goVer = GoVersionUtils.extractGoVersionFromMod(content);
        this.goModuleService.updateGoVersion(repoInfo, modulePath, version, goVer);
      }

      context.addProperty(USAGES, usages);
      return;
    }

    final var usages =
        this.goStorageService.writeInputStreamToPath(storagePath, inputStream, repoInfo.getName());

    if (path.endsWith(ZIP_EXTENSION)) {
      this.generateInfoFileIfAbsent(repoInfo, path);

      final var modulePath = GoVersionUtils.extractModulePath(path);
      final var version = GoVersionUtils.extractVersionFromZipPath(path);
      if (modulePath != null) {
        this.goModuleService.createOrUpdateModule(repoInfo, modulePath, version);
      }
    }

    context.addProperty(USAGES, usages);
  }

  private Resource handleVersionList(final BaseRepoInfo<ID> repoInfo, final String path) {
    final var atVPath = path.substring(0, path.length() - "list".length());
    final var atVStoragePath = StoragePath.of(repoInfo.getStorageKey(), atVPath);

    final var versions =
        this.goStorageService.listDirectory(atVStoragePath).stream()
            .filter(item -> !item.isDirectory() && item.getName().endsWith(INFO_EXTENSION))
            .map(item -> item.getName().substring(0, item.getName().length() - INFO_EXTENSION.length()))
            .sorted(GoVersionUtils.COMPARATOR)
            .collect(Collectors.joining("\n"));

    return new ByteArrayResource(versions.getBytes(StandardCharsets.UTF_8));
  }

  private Resource handleLatestVersion(final BaseRepoInfo<ID> repoInfo, final String path) {
    final var modulePath = path.substring(0, path.length() - LATEST_SUFFIX.length());
    final var atVPath = modulePath + "/@v/";
    final var atVStoragePath = StoragePath.of(repoInfo.getStorageKey(), atVPath);

    final var latestVersion =
        this.goStorageService.listDirectory(atVStoragePath).stream()
            .filter(item -> !item.isDirectory() && item.getName().endsWith(INFO_EXTENSION))
            .map(item -> item.getName().substring(0, item.getName().length() - INFO_EXTENSION.length()))
            .max(GoVersionUtils.COMPARATOR)
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    final var infoPath = modulePath + "/@v/" + latestVersion + INFO_EXTENSION;
    final var infoStoragePath = StoragePath.of(repoInfo.getStorageKey(), infoPath);
    return this.goStorageService.getResource(repoInfo.getName(), infoStoragePath);
  }

  @SneakyThrows
  private void generateInfoFileIfAbsent(final BaseRepoInfo<ID> repoInfo, final String zipPath) {
    final var infoPath = zipPath.substring(0, zipPath.length() - ZIP_EXTENSION.length()) + INFO_EXTENSION;
    final var infoStoragePath = StoragePath.of(repoInfo.getStorageKey(), infoPath);

    try {
      this.goStorageService.getResource(repoInfo.getName(), infoStoragePath);
    } catch (final ItemNotFoundException _) {
      final var version = GoVersionUtils.extractVersionFromZipPath(zipPath);
      final var versionInfo = GoVersionInfo.builder().version(version).time(Instant.now()).build();
      final var infoJson = OBJECT_MAPPER.writeValueAsString(versionInfo);

      try (final var infoStream =
          new ByteArrayInputStream(infoJson.getBytes(StandardCharsets.UTF_8))) {
        this.goStorageService.writeInputStreamToPath(infoStoragePath, infoStream, repoInfo.getName());
      }
    }
  }
}
