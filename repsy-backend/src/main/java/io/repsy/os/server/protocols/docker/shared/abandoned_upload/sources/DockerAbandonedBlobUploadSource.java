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
package io.repsy.os.server.protocols.docker.shared.abandoned_upload.sources;

import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.shared.sources.AbandonedBlobUploadSource;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.RepoRef;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Docker layers (RPS-1041, RPS-1172): an upload-session file is collectable unless a layer row
 * carries that UUID as its id (a layer stored under its own id is finalized, not an upload), a
 * digest file once no layer row of the repo carries the digest.
 */
@Component
@RequiredArgsConstructor
public class DockerAbandonedBlobUploadSource implements AbandonedBlobUploadSource {

  private final @NonNull LayerRepository layerRepository;
  private final @NonNull DockerStorageService dockerStorageService;

  @Override
  public @NonNull RepoType repoType() {
    return RepoType.DOCKER;
  }

  @Override
  public @NonNull List<StaleFile> listStaleBlobFiles(
      final @NonNull UUID repoId, final @NonNull Instant notModifiedSince) {
    return this.dockerStorageService.listStaleBlobFiles(repoId, notModifiedSince);
  }

  @Override
  public @NonNull Predicate<StaleFile> collectableIn(final @NonNull UUID repoId) {
    return file ->
        UPLOAD_SESSION_NAME.matcher(file.name()).matches()
            ? !this.layerRepository.existsByIdAndRepoId(UUID.fromString(file.name()), repoId)
            : !this.layerRepository.existsByRepoIdAndDigest(repoId, file.name());
  }

  @Override
  public long deleteBlobFile(
      final @NonNull UUID repoId, final @NonNull String repoName, final @NonNull String fileName)
      throws IOException {
    return this.dockerStorageService.deleteBlobFile(new RepoRef(repoId, repoName), fileName);
  }
}
