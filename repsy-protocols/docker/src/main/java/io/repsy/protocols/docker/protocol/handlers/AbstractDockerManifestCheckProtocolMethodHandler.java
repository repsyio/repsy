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
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.handlers.AbstractOciManifestCheckProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpMethod;

/**
 * The Docker manifest check: a missing image or manifest is the facade's exception, so the 404
 * carries the OCI error body.
 */
public abstract class AbstractDockerManifestCheckProtocolMethodHandler<ID>
    extends AbstractOciManifestCheckProtocolMethodHandler<DockerProtocolFacade<ID>> {

  public AbstractDockerManifestCheckProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.HEAD), basePathParser, dockerFacade, provider);
  }

  @Override
  protected Optional<OciManifestInfo> findManifest(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String reference)
      throws Exception {

    return Optional.of(
        DockerManifests.toOci(
            this.facade.getManifest(context, reference, name, request.getServletPath())));
  }
}
