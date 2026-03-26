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
package io.repsy.protocols.pypi.shared.storage.services;

import freemarker.template.TemplateException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

@NullMarked
public interface PypiStorageService<ID> {
  long deletePackage(UUID repoUuid, String packageNormalizedName);

  long deleteRelease(UUID repoUuid, String packageNormalizedName, String releaseVersion);

  Resource getArchiveFile(UUID repoUuid, String repoName, String packageName, String fileName);

  ByteArrayResource getPackageArchiveFileList(
      BaseRepoInfo<ID> repoInfo,
      String packageName,
      Map<String, String> versionAndRequiresPythonMap)
      throws IOException, TemplateException;

  long deleteRepo(UUID repoUuid);

  void createRepo(UUID repoUuid);

  void clearTrash();

  boolean isPackageFileExist(UUID repoId, String normalizedName, String version, String filename);

  BaseUsages writePackageArchive(
      UUID repoId, String repoName, PackageUploadForm uploadForm, MultipartFile file)
      throws IOException;
}
