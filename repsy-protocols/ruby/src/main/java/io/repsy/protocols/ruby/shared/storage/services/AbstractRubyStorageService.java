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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.storage.AbstractArtifactStorageService;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.InputStream;
import java.nio.file.Paths;
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
      final RepoRef repo,
      final String gemName,
      final String version,
      final String platform,
      final InputStream gem) {

    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repo.id(), gemPath.toString());

    return this.storageStrategy.write(repo.name(), storagePath, gem);
  }

  @Override
  public Resource getGem(
      final RepoRef repo, final String gemName, final String version, final String platform) {
    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repo.id(), gemPath.toString());

    return this.requireResource(storagePath, repo.name(), ProtocolErrorCodes.GEM_NOT_FOUND);
  }

  @Override
  public long deleteGem(
      final RepoRef repo, final String gemName, final String version, final String platform) {

    final var filename = buildFilename(gemName, version, platform);
    final var gemPath = Paths.get(GEMS_PATH, gemName, filename);
    final var storagePath = StoragePath.of(repo.id(), gemPath.toString());

    return this.deleteFileWithUsage(storagePath, repo.name());
  }

  @Override
  public long deleteAllGems(final RepoRef repo, final String gemName) {
    final var gemPath = Paths.get(GEMS_PATH, gemName);
    final var storagePath = StoragePath.of(repo.id(), gemPath.toString());

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
