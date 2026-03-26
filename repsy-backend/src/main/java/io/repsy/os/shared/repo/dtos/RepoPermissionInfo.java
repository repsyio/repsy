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
package io.repsy.os.shared.repo.dtos;

import io.repsy.os.shared.auth.dtos.PermissionInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPermissionInfo {
  private String repoName;
  private String description;
  private boolean isPrivate;
  private boolean canRead;
  private boolean canWrite;
  private boolean canManage;

  public static @NonNull RepoPermissionInfo buildPublicReadOnlyPermissions(
      final @NonNull RepoInfo repoInfo) {

    return RepoPermissionInfo.builder()
        .repoName(repoInfo.getName())
        .description(repoInfo.getDescription())
        .isPrivate(false)
        .canRead(true)
        .canWrite(false)
        .canManage(false)
        .build();
  }

  public static @NonNull RepoPermissionInfo buildRepoPermissionInfo(
      final @NonNull RepoInfo repoInfo, final @NonNull PermissionInfo permissionInfo) {

    return RepoPermissionInfo.builder()
        .repoName(repoInfo.getName())
        .description(repoInfo.getDescription())
        .isPrivate(repoInfo.isPrivateRepo())
        .canRead(permissionInfo.isCanRead())
        .canWrite(permissionInfo.isCanWrite())
        .canManage(permissionInfo.isCanManage())
        .build();
  }
}
