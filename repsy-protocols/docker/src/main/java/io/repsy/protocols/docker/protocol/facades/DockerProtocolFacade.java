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
import io.repsy.protocols.docker.shared.tag.dtos.ManifestDetails;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
public interface DockerProtocolFacade<ID> {

  /**
   * Appends a chunk to the layer upload, so a layer sent in several {@code PATCH} requests is
   * stored whole.
   *
   * @return The size of the whole upload so far, which is what the {@code Range} header reports
   */
  long uploadLayerChunk(
      ProtocolContext context,
      RelativePath relativePath,
      InputStream inputStream,
      long contentLength)
      throws IOException;

  /**
   * Checks that the finished upload hashes to the digest the client claims for it.
   *
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException When it does not
   */
  void verifyLayerDigest(ProtocolContext context, RelativePath relativePath, String digest)
      throws IOException;

  void finalizeLayerUpload(ProtocolContext context, RelativePath relativePath, LayerInfo layerInfo)
      throws IOException;

  @Nullable String saveManifest(
      ProtocolContext context, BaseImageInfo<ID> imageInfo, ManifestForm form) throws IOException;

  Resource getLayer(ProtocolContext context, String digest, String servletPath) throws IOException;

  ManifestDetails getManifest(
      ProtocolContext context, String manifestReference, String imageName, String requestPath)
      throws IOException;
}
