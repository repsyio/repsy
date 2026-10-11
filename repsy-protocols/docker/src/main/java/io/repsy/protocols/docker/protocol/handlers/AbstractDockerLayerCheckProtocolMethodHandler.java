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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.oci.dtos.OciBlobInfo;
import io.repsy.protocols.oci.handlers.AbstractOciBlobCheckProtocolMethodHandler;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BlobDigests;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.util.Optional;
import org.springframework.http.HttpMethod;

/** The OCI blob check of a Docker layer, any supported digest algorithm. */
public abstract class AbstractDockerLayerCheckProtocolMethodHandler<ID>
    extends AbstractOciBlobCheckProtocolMethodHandler<LayerService<ID>> {

  public AbstractDockerLayerCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final LayerService<ID> layerTxService,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.HEAD),
        OciPathUtils.blob(BlobDigests.DIGEST_REGEX),
        basePathParser,
        layerTxService,
        provider);
  }

  @Override
  protected Optional<OciBlobInfo> findBlob(final ProtocolContext context, final String digest) {
    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.facade
        .findLayerInfoByRepoIdAndDigest(repoInfo.getId(), digest)
        .map(layer -> new OciBlobInfo(layer.getSize(), layer.getMediaType()));
  }
}
