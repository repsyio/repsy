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

import freemarker.template.TemplateException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

@NullMarked
public interface PypiProtocolFacade<ID> {

  void uploadPackage(ProtocolContext context, Map<String, Object> parameterMap, MultipartFile file)
      throws IOException;

  ByteArrayResource getPackageList(ProtocolContext context, String packageNormalizedName)
      throws IOException, TemplateException;

  Resource downloadArchiveFile(ProtocolContext context, String packageName, String fileName)
      throws IOException;

  ByteArrayResource fetchFromLocalStorage(BaseRepoInfo<ID> repoInfo, String packageNormalizedName)
      throws TemplateException, IOException;
}
