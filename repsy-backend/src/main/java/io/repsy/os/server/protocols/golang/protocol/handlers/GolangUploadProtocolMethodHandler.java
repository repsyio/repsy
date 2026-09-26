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
package io.repsy.os.server.protocols.golang.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.golang.protocol.facades.contracts.GoProtocolFacade;
import io.repsy.protocols.golang.protocol.handlers.AbstractGoUploadProtocolMethodHandler;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class GolangUploadProtocolMethodHandler extends AbstractGoUploadProtocolMethodHandler<UUID> {

  private final MessageSource messageSource;

  public GolangUploadProtocolMethodHandler(
      @Qualifier("osGolangPathParser") final PathParser pathParser,
      final GoProtocolFacade<UUID> goProtocolFacade,
      final GolangProtocolProvider provider,
      final MessageSource messageSource) {

    super(pathParser, goProtocolFacade, provider);
    this.messageSource = messageSource;
  }

  /** The message the auth pre-processor answers for the same id (RPS-1450). */
  @Override
  protected String unauthorizedText(final @Nullable String msgId) {
    final var id = msgId == null ? "unAuthorized" : msgId;

    return this.messageSource.getMessage(id, null, id, Locale.getDefault());
  }
}
