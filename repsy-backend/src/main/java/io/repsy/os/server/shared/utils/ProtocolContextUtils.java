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

import com.github.f4b6a3.uuid.UuidCreator;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

@UtilityClass
public class ProtocolContextUtils {

  private static final @NonNull String URL_PROPERTIES = "urlProperties";

  public static @NonNull RepoInfo getRepoInfo(final @NonNull ProtocolContext context) {

    return getUrlProperties(context).getRepoInfo();
  }

  public static @NonNull RelativePath getRelativePath(final @NonNull ProtocolContext context) {

    return getUrlProperties(context).getRelativePath();
  }

  public static @NonNull UrlParserProperties getUrlProperties(
      final @NonNull ProtocolContext context) {

    return context.getProperty(URL_PROPERTIES);
  }

  public static @NonNull ProtocolContext createWithEmptyRepo(
      final @NonNull String repoName, final @NonNull RelativePath relativePath) {

    final var context = new ProtocolContext();

    final var emptyRepoInfo =
        RepoInfo.builder().storageKey(UuidCreator.getTimeOrderedEpoch()).name("").build();

    final var urlProperties =
        UrlParserProperties.builder()
            .repoName(repoName)
            .relativePath(relativePath)
            .repoInfo(emptyRepoInfo)
            .build();

    context.addProperty("urlProperties", urlProperties);

    return context;
  }
}
