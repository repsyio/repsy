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
package io.repsy.protocols.helm.protocol.handlers.oci;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.oci.dtos.OciBlobInfo;
import io.repsy.protocols.oci.handlers.AbstractOciBlobCheckProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

/** Handles HEAD /v2/{repo}/{name}/blobs/{digest} — checks if a blob exists. */
@NullMarked
public abstract class AbstractHelmOciBlobCheckProtocolMethodHandler<ID>
    extends AbstractOciBlobCheckProtocolMethodHandler<HelmProtocolFacade<ID>> {

  public AbstractHelmOciBlobCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.HEAD).writeOperation(false),
        HelmOciPaths.BLOB,
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  protected Optional<OciBlobInfo> findBlob(final ProtocolContext context, final String digest) {
    return this.facade
        .checkBlob(context, digest)
        .map(blob -> new OciBlobInfo(blob.size(), blob.mediaType()));
  }
}
