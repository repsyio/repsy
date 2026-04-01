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
package io.repsy.protocols.golang.shared.storage.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.SneakyThrows;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface GoStorageService<ID> {

  void createRepo(UUID repoUuid);

  BaseUsages getUsages(StoragePath storagePath, String repoName, long contentLength)
      throws IOException;

  @SneakyThrows
  Resource getResource(String repoName, StoragePath storagePath);

  @SneakyThrows
  BaseUsages writeInputStreamToPath(
      StoragePath storagePath, InputStream inputStream, String repoName);

  List<StorageItemInfo> listDirectory(StoragePath storagePath);

  void deleteDirectory(StoragePath storagePath);

  void deleteVersionFiles(StoragePath atVVersionBasePath, String repoName);

  long deleteRepo(UUID repoUuid);
}
