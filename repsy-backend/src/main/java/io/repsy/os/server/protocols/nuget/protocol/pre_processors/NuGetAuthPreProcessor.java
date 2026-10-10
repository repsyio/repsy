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
package io.repsy.os.server.protocols.nuget.protocol.pre_processors;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.os.server.protocols.nuget.shared.auth.services.NuGetAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.nuget.protocol.NuGetProtocolProvider;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * NuGet: Basic or Bearer, or the API key {@code nuget push -ApiKey} sends in {@code X-NuGet-ApiKey}
 * (a token without a scheme). Every refusal, a missing permission included, is a 401 with the Basic
 * challenge and no body, which the NuGet client answers by asking for credentials.
 */
@Component
@NullMarked
public class NuGetAuthPreProcessor extends BasicOrBearerAuthPreProcessor<NuGetAuthenticator> {

  private static final String X_NUGET_API_KEY = "X-NuGet-ApiKey";

  public NuGetAuthPreProcessor(
      final NuGetAuthenticator authenticator, final NuGetProtocolProvider provider) {
    super(authenticator, provider);
  }

  /** The {@code Authorization} header, or else the API key; a blank value is no credential. */
  @Override
  protected @Nullable String credential(final HttpServletRequest request) {

    final var authHeader = request.getHeader(AUTHORIZATION);

    if (authHeader != null && !authHeader.isBlank()) {
      return authHeader;
    }

    final var nugetApiKey = request.getHeader(X_NUGET_API_KEY);

    return nugetApiKey != null && !nugetApiKey.isBlank() ? nugetApiKey : null;
  }

  @Override
  protected boolean acceptsBareToken() {
    return true;
  }

  @Override
  protected void authenticateBasic(
      final String credential, final UUID repoId, final Permission permission) {

    try {
      super.authenticateBasic(credential, repoId, permission);
    } catch (final AccessNotAllowedException ex) {
      throw refusal(ex);
    }
  }

  @Override
  protected void authenticateBearer(
      final String credential, final UUID repoId, final Permission permission) {

    try {
      super.authenticateBearer(credential, repoId, permission);
    } catch (final AccessNotAllowedException ex) {
      throw refusal(ex);
    }
  }

  private static UnAuthorizedException refusal(final AccessNotAllowedException cause) {

    final var refusal = new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
    refusal.initCause(cause);
    return refusal;
  }

  @Override
  protected ProcessorResult refused(
      final UnAuthorizedException exception,
      final @Nullable String credential,
      final ProtocolContext context,
      final HttpServletRequest request,
      final Map<String, Object> properties) {

    return this.missingCredential(request);
  }
}
