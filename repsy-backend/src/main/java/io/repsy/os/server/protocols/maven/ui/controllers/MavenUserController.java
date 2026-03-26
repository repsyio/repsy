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
package io.repsy.os.server.protocols.maven.ui.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.server.protocols.maven.shared.auth.services.MavenAuthComponent;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn/user")
public class MavenUserController {

  private final @NonNull MavenAuthComponent mavenAuthComponent;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull RestResponseFactory restResponseFactory;

  @GetMapping("/{repoName}/can-manage")
  public @NonNull RestResponse<Boolean> canManage(
      @PathVariable final @NonNull String repoName,
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);
    final var repoPermissionInfo =
        this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.READ);

    return this.restResponseFactory.success("permissionChecked", repoPermissionInfo.isCanManage());
  }
}
