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
package io.repsy.protocols.pypi.protocol.facades;

import static io.repsy.protocols.pypi.shared.utils.PackageUtils.parseUploadForm;

import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.pypi.shared.python_package.dtos.ReleaseVersionRequiresPython;
import io.repsy.protocols.pypi.shared.python_package.services.PypiPackageService;
import io.repsy.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.protocols.pypi.shared.utils.PackageStorageUtils;
import io.repsy.protocols.pypi.shared.utils.PackageUtils;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractPypiProtocolFacade<ID> implements PypiProtocolFacade<ID> {

  private final PypiStorageService<ID> pypiStorageService;
  private final PypiPackageService<ID> pypiPackageService;

  @Override
  public void uploadPackage(
      final ProtocolContext context,
      final Map<String, Object> parameterMap,
      final MultipartFile file)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var uploadForm = parseUploadForm(parameterMap);
    uploadForm.setNormalizedName(PackageUtils.normalizePackageName(uploadForm.getName()));

    PackageStorageUtils.checkArchiveFilename(file);

    this.checkOverridePermission(repoInfo, uploadForm, file);

    // This method calculate on Disk Usages (If override calculate diff)
    final var packageUsage = this.savePackage(repoInfo, uploadForm, file);

    context.addProperty("usages", packageUsage);
  }

  @Override
  public Resource downloadArchiveFile(
      final ProtocolContext context, final String packageName, final String fileName)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    return this.pypiStorageService.getArchiveFile(
        repoInfo.getStorageKey(), repoInfo.getName(), packageName, fileName);
  }

  @Override
  public ByteArrayResource getPackageList(
      final ProtocolContext context, final String packageNormalizedName)
      throws IOException, TemplateException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.fetchFromLocalStorage(repoInfo, packageNormalizedName);
  }

  @Override
  public ByteArrayResource fetchFromLocalStorage(
      final BaseRepoInfo<ID> repoInfo, final String packageNormalizedName)
      throws TemplateException, IOException {

    final var packageInfo =
        this.pypiPackageService.getPackage(repoInfo.getId(), packageNormalizedName);

    final var requiresPythonMap =
        this.pypiPackageService.getReleaseIndexListItemInfos(packageInfo.getId()).stream()
            .collect(
                Collectors.toMap(
                    ReleaseVersionRequiresPython::getVersion,
                    r -> r.getRequiresPython().replace("<", "&lt;").replace(">", "&gt;")));

    return this.pypiStorageService.getPackageArchiveFileList(
        repoInfo, packageNormalizedName, requiresPythonMap);
  }

  private void checkOverridePermission(
      final BaseRepoInfo<ID> repoInfo,
      final PackageUploadForm uploadForm,
      final MultipartFile file) {

    if (repoInfo.isAllowOverride()) {
      return;
    }

    final var fileExists =
        this.pypiStorageService.isPackageFileExist(
            repoInfo.getStorageKey(),
            uploadForm.getNormalizedName(),
            uploadForm.getVersion(),
            Objects.requireNonNull(file.getOriginalFilename()));

    if (fileExists) {
      throw new AccessNotAllowedException("fileAlreadyExists");
    }
  }

  private BaseUsages savePackage(
      final BaseRepoInfo<ID> repoInfo, final PackageUploadForm uploadForm, final MultipartFile file)
      throws IOException {

    final var usages =
        this.pypiStorageService.writePackageArchive(
            repoInfo.getStorageKey(), repoInfo.getName(), uploadForm, file);

    this.pypiPackageService.addOrUpdateRelease(repoInfo, uploadForm);

    return usages;
  }
}
