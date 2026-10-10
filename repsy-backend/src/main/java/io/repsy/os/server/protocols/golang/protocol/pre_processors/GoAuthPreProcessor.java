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
package io.repsy.os.server.protocols.golang.protocol.pre_processors;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.os.server.protocols.golang.shared.auth.services.GoAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.golang.protocol.GolangProtocolProvider;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Go: Basic or Bearer, every refusal answered as text the go command prints. */
@Component
@NullMarked
public class GoAuthPreProcessor extends BasicOrBearerAuthPreProcessor<GoAuthenticator> {

  private final MessageSource messageSource;

  public GoAuthPreProcessor(
      final GoAuthenticator authenticator,
      final MessageSource messageSource,
      final GolangProtocolProvider provider) {

    super(authenticator, provider);
    this.messageSource = messageSource;
  }

  @Override
  protected ProcessorResult missingCredential(final HttpServletRequest request) {
    return ProcessorResult.of(this.unauthorized(ProtocolErrorCodes.UNAUTHORIZED_REQUEST));
  }

  @Override
  protected ProcessorResult refused(
      final UnAuthorizedException exception,
      final @Nullable String credential,
      final ProtocolContext context,
      final HttpServletRequest request,
      final Map<String, Object> properties) {

    return ProcessorResult.of(
        this.unauthorized(
            Objects.toString(exception.getMessage(), ProtocolErrorCodes.UN_AUTHORIZED)));
  }

  /**
   * The go command prints the body of a failed answer only when it is {@code text/plain}, and shows
   * nothing but the status for the panel's JSON envelope or an empty body (RPS-1435). The message
   * is the one the credential check chose: the generic {@code unAuthorized}, which does not tell an
   * unknown user from a wrong password, or {@code deployTokenExpired}.
   */
  private ResponseEntity<Object> unauthorized(final String msgId) {

    final var text = this.messageSource.getMessage(msgId, null, msgId, Locale.getDefault());

    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(WWW_AUTHENTICATE, this.challenge(null))
        .contentType(MediaType.TEXT_PLAIN)
        .body(text);
  }
}
