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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.handlers.AbstractDockerTokenProtocolMethodHandler;
import io.repsy.protocols.docker.protocol.parser.DockerScopeParser;
import io.repsy.protocols.docker.shared.auth.services.DockerAuthService;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class DockerTokenProtocolMethodHandler
    extends AbstractDockerTokenProtocolMethodHandler<UUID> {

  public DockerTokenProtocolMethodHandler(
      final DockerAuthService<UUID> authService,
      final DockerScopeParser<UUID> scopeParserUtils,
      final DockerProtocolProvider provider) {
    super(authService, scopeParserUtils, provider);
  }

  @Override
  protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
    return Optional.of(ProtocolContextUtils.createWithEmptyRepo("", relativePath));
  }
}
