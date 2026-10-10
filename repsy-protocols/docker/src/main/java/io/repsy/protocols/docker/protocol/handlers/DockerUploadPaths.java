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
package io.repsy.protocols.docker.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.shared.http.PublicUrls;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/** Where a Docker upload session is stored and the URLs Docker reports for it. */
@UtilityClass
@NullMarked
final class DockerUploadPaths {

  /** The storage path of the session's bytes. */
  static RelativePath of(final String sessionId) {
    return new RelativePath("/blobs/" + sessionId);
  }

  /** {@code /v2/<repo>/<image>/blobs/uploads/<id>} under the current context path. */
  static String sessionLocation(
      final ProtocolContext context, final String imageName, final String sessionId) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return PublicUrls.currentContextRoot()
        + "/v2/"
        + urlProperties.getRepoName()
        + "/"
        + imageName
        + "/blobs/uploads/"
        + sessionId;
  }
}
