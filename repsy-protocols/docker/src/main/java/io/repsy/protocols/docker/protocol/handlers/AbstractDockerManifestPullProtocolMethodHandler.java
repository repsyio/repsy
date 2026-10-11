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

import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_LIST;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.DOCKER_MANIFEST_SCHEMA2;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_IMAGE_INDEX;
import static io.repsy.protocols.docker.shared.utils.MediaTypes.OCI_MANIFEST_SCHEMA1;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.utils.AcceptHeaderParser;
import io.repsy.protocols.docker.shared.utils.MediaTypes;
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.handlers.AbstractOciManifestPullProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

/**
 * The Docker manifest pull: negotiates the {@code Accept} header first, so a client that accepts no
 * manifest type this registry serves gets a 406 before the manifest is read.
 */
public abstract class AbstractDockerManifestPullProtocolMethodHandler<ID>
    extends AbstractOciManifestPullProtocolMethodHandler<DockerProtocolFacade<ID>> {

  private static final List<String> DEFAULT_DOCKER_ACCEPT_TYPES =
      List.of(DOCKER_MANIFEST_SCHEMA2, DOCKER_MANIFEST_LIST, OCI_MANIFEST_SCHEMA1, OCI_IMAGE_INDEX);

  public AbstractDockerManifestPullProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(HandlerRoute.of(Permission.READ, HttpMethod.GET), basePathParser, dockerFacade, provider);
  }

  @Override
  protected void checkAcceptable(final HttpServletRequest request)
      throws HttpMediaTypeNotAcceptableException {

    final var acceptHeaders =
        AcceptHeaderParser.parse(request.getHeader("Accept"), DEFAULT_DOCKER_ACCEPT_TYPES);

    if (MediaTypes.getPreferredMediaType(acceptHeaders) == null) {
      throw new HttpMediaTypeNotAcceptableException(
          "The Accept header names no manifest media type this repository serves.");
    }
  }

  @Override
  protected OciManifestInfo getManifest(
      final ProtocolContext context,
      final HttpServletRequest request,
      final String name,
      final String reference)
      throws Exception {

    return DockerManifests.toOci(
        this.facade.getManifest(context, reference, name, request.getServletPath()));
  }
}
