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
package io.repsy.protocols.shared.storage;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.UUID;

/**
 * The repo a storage service works in: the storage key that names its directory and the repo name
 * the storage strategy is given for what it logs and accounts. One type for every format, so a
 * service method takes {@code (repo, ...)} instead of a loose {@code (UUID, String)} pair that a
 * caller could swap or fill from two different places.
 *
 * <p>Calls that only address the directory (creating or removing the repo, listing stale blobs)
 * still take the bare storage key: they never need the name.
 *
 * @param id the storage key, the name of the repo's directory
 * @param name the repo name
 */
public record RepoRef(UUID id, String name) {

  public static RepoRef of(final BaseRepoInfo<?> repoInfo) {
    return new RepoRef(repoInfo.getStorageKey(), repoInfo.getName());
  }
}
