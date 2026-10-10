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
package io.repsy.os.server.protocols.shared.sources;

import io.repsy.libs.storage.core.dtos.StaleFile;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;

/**
 * What a protocol that writes blobs through an upload session tells the abandoned blob upload
 * cleanup (RPS-2055): which of its repos to sweep, which stale files it holds, which of them
 * nothing references, and how to delete one. The cleanup itself, the pass lock, the usage release
 * and the error handling stay in {@code AbandonedBlobUploadCleanupService}, which knows no
 * protocol.
 */
public interface AbandonedBlobUploadSource {

  /** A file named after an upload session, {@code blobs/<uuid>}, is not finalized yet. */
  Pattern UPLOAD_SESSION_NAME =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /** The type of the repos this source sweeps. */
  @NonNull RepoType repoType();

  /** The blob files of the repo last written before {@code notModifiedSince}. */
  @NonNull List<StaleFile> listStaleBlobFiles(
      @NonNull UUID repoId, @NonNull Instant notModifiedSince);

  /**
   * Decides, for a stale file of the repo, whether nothing references it any more. Called once per
   * repo and pass, only when the repo has at least one stale file, so an implementation may load
   * what it needs to decide up front.
   */
  @NonNull Predicate<StaleFile> collectableIn(@NonNull UUID repoId);

  /**
   * Deletes the blob file, and any row that only that file owned.
   *
   * @return the bytes released
   */
  long deleteBlobFile(@NonNull UUID repoId, @NonNull String repoName, @NonNull String fileName)
      throws IOException;
}
