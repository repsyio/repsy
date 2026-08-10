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
package io.repsy.os.server.shared.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@UtilityClass
public class RequestBaseUrlUtils {

  private static final int PORT_HTTPS = 443;
  private static final int PORT_HTTP = 80;
  private static final String PROTO_HTTPS = "https";
  private static final String PROTO_HTTP = "http";

  public static @NonNull String resolveBaseUrl(final @NonNull HttpServletRequest request) {
    final var scheme = request.getScheme();
    final var host = request.getServerName();
    final var port = request.getServerPort();
    return scheme + "://" + (isDefaultPort(scheme, port) ? host : host + ":" + port);
  }

  private static boolean isDefaultPort(final String scheme, final int port) {
    return (PROTO_HTTP.equals(scheme) && port == PORT_HTTP)
        || (PROTO_HTTPS.equals(scheme) && port == PORT_HTTPS);
  }

  public static @NonNull String resolveBaseUrl() {
    try {
      final var requestAttributes = RequestContextHolder.getRequestAttributes();
      if (requestAttributes instanceof final ServletRequestAttributes servletRequestAttributes) {
        return resolveBaseUrl(servletRequestAttributes.getRequest());
      }
    } catch (final Exception e) {
      log.debug(
          "Could not resolve base URL from request context, falling back to empty string. Error: {}",
          e.getMessage());
    }
    return "";
  }
}
