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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.generated.model.KeyStoreItem;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn/key-stores")
@NullMarked
@SuppressWarnings("java:S6856")
public class KeyStoreController {

  private final KeyStoreService keyStoreService;
  private final RestResponseFactory restResponseFactory;

  @PostMapping("/{repoName}")
  @RepoOperation(permission = Permission.WRITE)
  public RestResponse<KeyStoreItem> create(
      final RepoInfo repoInfo, @RequestBody final KeyStoreForm form) {

    this.keyStoreService.create(repoInfo, form);

    return this.restResponseFactory.success("keyStoreCreated");
  }

  @DeleteMapping("/{repoName}/{keyStoreId}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<KeyStoreItem> delete(
      final RepoInfo repoInfo, @PathVariable final UUID keyStoreId) {

    this.keyStoreService.delete(repoInfo, keyStoreId);

    return this.restResponseFactory.success("keyStoreDeleted");
  }

  @GetMapping("/{repoName}")
  @RepoOperation(permission = Permission.MANAGE)
  public RestResponse<PagedModel<KeyStoreItem>> list(
      final RepoInfo repoInfo,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    final var result = this.keyStoreService.findAll(repoInfo, pageable);

    return this.restResponseFactory.success("keyStoresFetched", new PagedModel<>(result));
  }
}
