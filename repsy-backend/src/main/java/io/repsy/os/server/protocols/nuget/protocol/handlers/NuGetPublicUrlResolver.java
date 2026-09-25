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
package io.repsy.os.server.protocols.nuget.protocol.handlers;

import io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver;
import io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The address the NuGet service index, registration and search name their URLs with: the configured
 * public URL ({@code repsy.nuget.public-url}, {@code REPO_BASE_URL}) when there is one, otherwise
 * the scheme, host and port of the request. It is the only way to name an address the request
 * cannot show, such as a path prefix a reverse proxy strips or a proxy that does not send {@code
 * X-Forwarded-*} (RPS-1432, as {@code repsy.npm.public-url} does for npm in RPS-1333).
 */
@Component
@NullMarked
public class NuGetPublicUrlResolver implements NuGetBaseUrlResolver {

  private final @Nullable String publicUrl;

  public NuGetPublicUrlResolver(@Value("${repsy.nuget.public-url:}") final String publicUrl) {

    final var stripped = publicUrl.strip().replaceAll("/+$", "");

    this.publicUrl = stripped.isEmpty() ? null : stripped;
  }

  @Override
  public String baseUrl(final HttpServletRequest request, final String repoName) {

    if (this.publicUrl != null) {
      return this.publicUrl + "/" + repoName;
    }

    return NuGetUrlBuilder.buildBaseUrl(request, repoName);
  }
}
