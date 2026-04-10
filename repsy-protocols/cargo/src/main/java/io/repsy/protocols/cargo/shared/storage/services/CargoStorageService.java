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
package io.repsy.protocols.cargo.shared.storage.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface CargoStorageService {

  BaseUsages writeCrateAndIndex(
      UUID repoId,
      String repoName,
      String crateName,
      String versionName,
      byte[] crateBytes,
      String indexJsonLine)
      throws IOException;

  Resource getCrate(UUID repoId, String repoName, String crateName, String versionName);

  long deleteCrate(UUID repoId, String repoName, String crateName, String versionName)
      throws IOException;

  long deletePackage(UUID repoId, String repoName, String crateName) throws IOException;

  long deleteRepo(UUID repoId);

  long rewriteIndex(UUID repoId, String repoName, String crateName, List<String> jsonLines)
      throws IOException;

  void createRepo(UUID repoId);
}
