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
package io.repsy.protocols.ruby.shared.storage.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractRubyStorageService implements RubyStorageService {

  private static final String GEMS_PATH = "gems";
  private static final String DEFAULT_PLATFORM = "ruby";
  private static final String GEM_FILE_FMT = "%s-%s.gem";
  private static final String GEM_FILE_PLATFORM_FMT = "%s-%s-%s.gem";
  // Gem versions always start with a digit; split name from version on first -\d
  private static final Pattern VERSION_START = Pattern.compile("-(?=\\d)");

  private final StorageStrategy storageStrategy;

  @Override
  public BaseUsages writeGem(
      final UUID repoId,
      final String repoName,
      final String gemName,
      final String version,
      final String platform,
      final byte[] bytes) {

    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    return this.storageStrategy.write(repoName, storagePath, new ByteArrayInputStream(bytes));
  }

  @Override
  public Resource getGem(final UUID repoId, final String repoName, final String filename) {
    final var gemName = extractGemName(filename);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    return this.storageStrategy
        .get(storagePath, repoName)
        .orElseThrow(() -> new ItemNotFoundException("gemNotFound"));
  }

  @Override
  public long deleteGem(
      final UUID repoId,
      final String repoName,
      final String gemName,
      final String version,
      final String platform) {

    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    try {
      final var usage = this.storageStrategy.getFileUsage(storagePath, repoName);
      this.storageStrategy.delete(storagePath);
      return usage;
    } catch (final IOException e) {
      throw new ItemNotFoundException("gemNotFound");
    }
  }

  @Override
  public long deleteAllGems(final UUID repoId, final String repoName, final String gemName) {
    final var gemPath = Paths.get(GEMS_PATH, gemName);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    final var usage = this.storageStrategy.calculatePathUsage(storagePath);
    this.storageStrategy.deleteDirectory(storagePath);
    return usage;
  }

  @Override
  public void createRepo(final UUID repoId) {
    this.storageStrategy.createDirectory(repoId.toString());
  }

  @Override
  public String getGemRelativePath(
      final String gemName, final String version, final String platform) {

    final var filename = buildFilename(gemName, version, platform);
    return Paths.get(GEMS_PATH, gemName, filename).toString();
  }

  @Override
  public long deleteRepo(final UUID repoId) {
    final var storagePath = StoragePath.of(repoId);
    final var usage = this.storageStrategy.calculatePathUsage(storagePath);
    this.storageStrategy.deleteDirectory(storagePath);
    return usage;
  }

  public static String buildFilename(
      final String gemName, final String version, final String platform) {
    return DEFAULT_PLATFORM.equals(platform)
        ? String.format(GEM_FILE_FMT, gemName, version)
        : String.format(GEM_FILE_PLATFORM_FMT, gemName, version, platform);
  }

  private static String extractGemName(final String filename) {
    final var noExt =
        filename.endsWith(".gem") ? filename.substring(0, filename.length() - 4) : filename;
    final var matcher = VERSION_START.matcher(noExt);
    return matcher.find() ? noExt.substring(0, matcher.start()) : noExt;
  }
}
