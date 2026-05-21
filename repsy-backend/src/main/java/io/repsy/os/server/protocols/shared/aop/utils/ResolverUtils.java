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
package io.repsy.os.server.protocols.shared.aop.utils;

import static org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST;
import static org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.RepoPermissionInfo;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.NativeWebRequest;

@NullMarked
@UtilityClass
public class ResolverUtils {

  public static final String REPO_INFO = "resolvedRepoInfo";
  public static final String REPO_PERMISSION_INFO = "repoPermissionInfo";
  public static final String REPO_TYPE = "repoType";
  public static final String REPO_NAME = "repoName";

  @SuppressWarnings("unchecked")
  public static Map<String, String> getUrlVariables(final NativeWebRequest webRequest) {

    final var uriVariables =
        (Map<String, String>)
            webRequest.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, SCOPE_REQUEST);

    if (uriVariables == null) {
      throw new ItemNotFoundException("repoNotFound");
    }

    return uriVariables;
  }

  public static @Nullable RepoInfo extractRepoInfo(final NativeWebRequest webRequest) {

    return (RepoInfo) webRequest.getAttribute(REPO_INFO, SCOPE_REQUEST);
  }

  public static RepoPermissionInfo extractRepoPermissionInfo(final NativeWebRequest webRequest) {

    return (RepoPermissionInfo)
        Objects.requireNonNull(webRequest.getAttribute(REPO_PERMISSION_INFO, SCOPE_REQUEST));
  }

  public static @Nullable String extractRepoInfo(final Map<String, String> uriVariables) {

    return uriVariables.get(REPO_NAME);
  }

  public static Optional<RepoType> extractRepoType(final Map<String, String> uriVariables) {

    final var repoType = uriVariables.get(REPO_TYPE);

    return RepoType.fromString(repoType);
  }

  public static Optional<RepoType> extractProtocolRepoType(final Map<String, String> uriVariables) {

    final var repoType = uriVariables.get(REPO_TYPE);

    return RepoType.fromString(repoType);
  }

  public static RepoType getRepoTypeIfExists(
      final NativeWebRequest webRequest, final @Nullable RepoInfo repoInfo) {

    if (repoInfo != null) {
      return repoInfo.getType();
    }

    final var repoTypeOpt = extractProtocolRepoType(getUrlVariables(webRequest));

    if (repoTypeOpt.isEmpty()) {
      throw new ItemNotFoundException("repoTypeNotFound");
    }

    return repoTypeOpt.get();
  }
}
