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
package io.repsy.protocols.nuget;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.UUID;

/** Builds the {@link ProtocolContext} the protocol router would hand to a NuGet handler. */
public final class NuGetTestContexts {

  public static final String REPO_NAME = "nuget";

  private NuGetTestContexts() {}

  public static BaseRepoInfo<UUID> repoInfo() {
    return BaseRepoInfo.<UUID>builder()
        .id(UUID.randomUUID())
        .storageKey(UUID.randomUUID())
        .name(REPO_NAME)
        .build();
  }

  public static ProtocolContext context(final String relativePath) {
    return context(relativePath, repoInfo());
  }

  public static ProtocolContext context(
      final String relativePath, final BaseRepoInfo<UUID> repoInfo) {

    final var urlProps =
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(repoInfo.getName())
            .relativePath(new RelativePath(relativePath))
            .repoInfo(repoInfo)
            .build();

    final var ctx = new ProtocolContext();
    ctx.addProperty("urlProperties", urlProps);
    return ctx;
  }
}
