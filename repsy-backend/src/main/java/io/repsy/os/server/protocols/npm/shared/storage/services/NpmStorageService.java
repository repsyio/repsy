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
package io.repsy.os.server.protocols.npm.shared.storage.services;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.os.server.shared.utils.RequestBaseUrlUtils;
import io.repsy.protocols.npm.shared.storage.services.AbstractNpmStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@NullMarked
public class NpmStorageService extends AbstractNpmStorageService {

  private final int repoPort;

  public NpmStorageService(
      @Qualifier("osStorageStrategyNpm") final StorageStrategy storageStrategy,
      @Value("${server.port:9090}") final int repoPort) {

    super(storageStrategy);

    this.repoPort = repoPort;
  }

  @Override
  protected @Nullable String registryBaseUrl() {

    if (RequestContextHolder.getRequestAttributes()
        instanceof final ServletRequestAttributes attributes) {
      return registryBaseUrl(attributes.getRequest(), this.repoPort);
    }

    return null;
  }

  /**
   * The address the registry answers at, for the {@code dist.tarball} of a rebuilt version. A
   * request to the registry port already carries it. The panel is served on another port, so for a
   * request there the registry port takes the panel's place, unless something in front of the
   * server (a reverse proxy) rewrites the port: then what the client sees is all there is to go by.
   */
  static String registryBaseUrl(final HttpServletRequest request, final int repoPort) {

    final var onPanelPort =
        request.getLocalPort() != repoPort && request.getServerPort() == request.getLocalPort();

    if (onPanelPort) {
      return request.getScheme() + "://" + request.getServerName() + ":" + repoPort;
    }

    return RequestBaseUrlUtils.resolveBaseUrl(request);
  }
}
