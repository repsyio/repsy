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
package io.repsy.protocols.pypi.shared.utils;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriComponentsBuilder;

@UtilityClass
@NullMarked
public class UriUtils {

  @Nullable
  public static URI normalizedUri(
      final HttpServletRequest httpServletRequest,
      final @Nullable String packageName,
      final String repoOrigin) {

    final var requestURI = httpServletRequest.getRequestURI();

    if ((packageName != null && !PackageUtils.isPackageNameNormalized(packageName))
        || !requestURI.endsWith("/")) {

      final var builder = UriComponentsBuilder.fromUriString(repoOrigin).path(requestURI);

      if (packageName != null) {
        return builder
            .pathSegment("..")
            .pathSegment(PackageUtils.normalizePackageName(packageName))
            .path("/")
            .build()
            .normalize()
            .toUri();
      }

      return builder.path("/").build().toUri();
    }

    return null;
  }
}
