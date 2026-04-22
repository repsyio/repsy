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

import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.dtos.RepoPermissionInfo;
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

  public static RepoInfo extractRepoInfo(final NativeWebRequest webRequest) {

    return (RepoInfo) Objects.requireNonNull(webRequest.getAttribute(REPO_INFO, SCOPE_REQUEST));
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
}
