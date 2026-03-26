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
package io.repsy.os.server.protocols.pypi.shared.storage.services;

import freemarker.template.Configuration;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.pypi.shared.storage.services.AbstractPypiStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@NullMarked
public class PypiStorageService extends AbstractPypiStorageService<UUID> {

  @Value("${os.app.repo-base-url}")
  private String repoBaseUrl;

  public PypiStorageService(
      @Qualifier("osStorageStrategyPypi") final StorageStrategy storageStrategy,
      final Configuration freeMarkerConfiguration) {

    super(storageStrategy, freeMarkerConfiguration);
  }

  @Override
  protected String buildRepoUri(final BaseRepoInfo<UUID> baseRepoInfo) {

    return this.constructRepoBaseUri(baseRepoInfo.getName(), this.repoBaseUrl);
  }

  private String constructRepoBaseUri(final String repoName, final String repoOrigin) {

    return UriComponentsBuilder.fromUriString(repoOrigin)
        .pathSegment(repoName)
        .build()
        .toUriString();
  }
}
