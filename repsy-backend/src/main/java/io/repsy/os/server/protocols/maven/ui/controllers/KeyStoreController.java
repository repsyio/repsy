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
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreForm;
import io.repsy.os.server.protocols.maven.shared.keystore.dtos.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn/key-stores")
public class KeyStoreController {

  private final @NonNull KeyStoreService keyStoreService;
  private final @NonNull RestResponseFactory restResponseFactory;
  private final @NonNull RepoTxService repoTxService;
  private final @NonNull MavenAuthComponent mavenAuthComponent;

  @PostMapping("/{repoName}")
  public @NonNull RestResponse<KeyStoreItem> create(
      @RequestHeader(AUTHORIZATION) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @RequestBody final @NonNull KeyStoreForm form) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.WRITE);

    this.keyStoreService.create(repoInfo, form);

    return this.restResponseFactory.success("keyStoreCreated");
  }

  @DeleteMapping("/{repoName}/{keyStoreId}")
  public @NonNull RestResponse<KeyStoreItem> delete(
      @RequestHeader(AUTHORIZATION) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PathVariable final @NonNull UUID keyStoreId) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    this.keyStoreService.delete(repoInfo, keyStoreId);

    return this.restResponseFactory.success("keyStoreDeleted");
  }

  @GetMapping("/{repoName}")
  public @NonNull RestResponse<PagedModel<KeyStoreItem>> findAll(
      @RequestHeader(AUTHORIZATION) final @Nullable String authHeader,
      @PathVariable final @NonNull String repoName,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC)
          final @NonNull Pageable pageable) {

    final var repoInfo = this.repoTxService.getRepo(repoName, RepoType.MAVEN);

    this.mavenAuthComponent.authorizeUserRequest(repoInfo, authHeader, Permission.MANAGE);

    final var result = this.keyStoreService.findAll(repoInfo, pageable);

    return this.restResponseFactory.success("keyStoresFetched", new PagedModel<>(result));
  }
}
