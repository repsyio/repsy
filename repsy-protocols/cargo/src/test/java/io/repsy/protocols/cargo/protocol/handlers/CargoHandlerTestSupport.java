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
package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/** Shared fixtures for the cargo protocol method handler tests. */
final class CargoHandlerTestSupport {

  static final String REPO_NAME = "cargo";

  private CargoHandlerTestSupport() {}

  static ProtocolContext context(final String relativePath) {
    return context(relativePath, false);
  }

  static ProtocolContext context(final String relativePath, final boolean privateRepo) {
    final var repoInfo =
        BaseRepoInfo.<UUID>builder()
            .storageKey(UUID.randomUUID())
            .name(REPO_NAME)
            .privateRepo(privateRepo)
            .build();

    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();

    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }

  static String errorDetail(final ResponseEntity<Object> response) {
    return ((CargoErrorResponse) response.getBody()).errors().getFirst().detail();
  }
}
