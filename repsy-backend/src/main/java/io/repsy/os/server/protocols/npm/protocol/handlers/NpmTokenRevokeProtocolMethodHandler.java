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
package io.repsy.os.server.protocols.npm.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.handlers.AbstractNpmTokenRevokeProtocolMethodHandler;
import io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class NpmTokenRevokeProtocolMethodHandler
    extends AbstractNpmTokenRevokeProtocolMethodHandler<UUID> {

  private final MessageSource messageSource;

  public NpmTokenRevokeProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmTokenRevoker<UUID> tokenRevoker,
      final NpmProtocolProvider provider,
      final MessageSource messageSource) {

    super(basePathParser, tokenRevoker, provider);
    this.messageSource = messageSource;
  }

  /** The message of the refusal, for example why a deploy token needs no logout (RPS-1391). */
  @Override
  protected String forbiddenText(final @Nullable String msgId) {
    final var id = msgId == null ? "accessNotAllowed" : msgId;

    return this.messageSource.getMessage(id, null, id, Locale.getDefault());
  }
}
