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
package io.repsy.protocols.nuget.shared.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

@NullMarked
@UtilityClass
public final class NuGetUrlBuilder {

  private static final int PORT_HTTPS = 443;
  private static final int PORT_HTTP = 80;
  private static final String PROTO_HTTPS = "HTTPS";
  private static final String PROTO_HTTP = "HTTP";

  public static String registrationBase(final String baseUrl, final String idLower) {

    return baseUrl + "/v3/registration/" + idLower;
  }

  public static String packageBase(final String baseUrl, final String idLower) {

    return baseUrl + "/v3/package/" + idLower;
  }

  public static String leafUrl(final String registrationBase, final String segment) {

    return registrationBase + "/" + segment + ".json";
  }

  public static String nupkgUrl(
      final String packageBase, final String idLower, final String versionLower) {

    return packageBase + "/" + versionLower + "/" + idLower + "." + versionLower + ".nupkg";
  }

  public static String buildBaseUrl(final HttpServletRequest request, final String repoName) {

    final var scheme = request.getScheme();
    final var host = request.getServerName();
    final var port = request.getServerPort();
    final var authority = buildAuthority(scheme, host, port);

    return scheme + "://" + authority + "/" + repoName;
  }

  private static String buildAuthority(final String scheme, final String host, final int port) {
    final var isDefaultPort =
        (PROTO_HTTP.equals(scheme) && port == PORT_HTTP)
            || (PROTO_HTTPS.equals(scheme) && port == PORT_HTTPS);

    return isDefaultPort ? host : host + ":" + port;
  }
}
