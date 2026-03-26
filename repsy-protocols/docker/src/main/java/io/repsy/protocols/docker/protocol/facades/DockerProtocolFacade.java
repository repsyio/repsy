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
package io.repsy.protocols.docker.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.util.Pair;

@NullMarked
public interface DockerProtocolFacade<ID> {

  long uploadLayerChunk(
      ProtocolContext context,
      RelativePath relativePath,
      InputStream inputStream,
      long contentLength)
      throws IOException;

  void finalizeLayerUpload(
      BaseRepoInfo<ID> repoInfo, RelativePath relativePath, LayerInfo layerInfo) throws IOException;

  @Nullable String saveManifest(
      ProtocolContext context, BaseImageInfo<ID> imageInfo, ManifestForm form) throws IOException;

  Resource getLayer(ProtocolContext context, String digest, String servletPath) throws IOException;

  ManifestDetails getManifest(
      ProtocolContext context, String manifestReference, String imageName, String requestPath)
      throws IOException;

  Pair<Optional<BaseTagDetail<ID>>, String> findTagAndManifest(
      ProtocolContext context, String reference, String imageName, RelativePath relativePath)
      throws IOException;
}
