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
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.storage.AbstractArtifactStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public abstract class AbstractRubyStorageService extends AbstractArtifactStorageService
    implements RubyStorageService {

  private static final String GEMS_PATH = "gems";
  private static final String DEFAULT_PLATFORM = "ruby";

  protected AbstractRubyStorageService(final StorageStrategy storageStrategy) {
    super(storageStrategy);
  }

  @Override
  public BaseUsages writeGem(
      final UUID repoId,
      final String repoName,
      final String gemName,
      final String version,
      final String platform,
      final InputStream gem) {

    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    return this.storageStrategy.write(repoName, storagePath, gem);
  }

  @Override
  public Resource getGem(
      final UUID repoId,
      final String repoName,
      final String gemName,
      final String version,
      final String platform) {
    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    return this.requireResource(storagePath, repoName, ProtocolErrorCodes.GEM_NOT_FOUND);
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
      return this.deleteFileWithUsage(storagePath, repoName);
    } catch (final IOException e) {
      throw new ItemNotFoundException(ProtocolErrorCodes.GEM_NOT_FOUND);
    }
  }

  @Override
  public long deleteAllGems(final UUID repoId, final String repoName, final String gemName) {
    final var gemPath = Paths.get(GEMS_PATH, gemName);
    final var storagePath = StoragePath.of(repoId, gemPath.toString());

    return this.deleteTreeWithUsage(storagePath);
  }

  @Override
  public String getGemRelativePath(
      final String gemName, final String version, final String platform) {

    final var filename = buildFilename(gemName, version, platform);
    return Paths.get(GEMS_PATH, gemName, filename).toString();
  }

  public static String buildFilename(
      final String gemName, final String version, final String platform) {
    return DEFAULT_PLATFORM.equals(platform)
        ? String.format("%s-%s.gem", gemName, version)
        : String.format("%s-%s-%s.gem", gemName, version, platform);
  }
}
