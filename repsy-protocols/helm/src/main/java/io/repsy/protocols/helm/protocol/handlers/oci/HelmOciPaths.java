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
package io.repsy.protocols.helm.protocol.handlers.oci;

import io.repsy.protocols.oci.utils.OciPathUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The Helm OCI blob path (sha256 digests only) and the Location Helm reports for a session. */
@UtilityClass
@NullMarked
final class HelmOciPaths {

  static final Pattern BLOB = OciPathUtils.blob("sha256:[0-9a-fA-F]{64}");

  /** The request's own URI under the current context path. */
  static String requestLocation(final HttpServletRequest request) {
    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path(request.getRequestURI())
        .build()
        .toUriString();
  }
}
