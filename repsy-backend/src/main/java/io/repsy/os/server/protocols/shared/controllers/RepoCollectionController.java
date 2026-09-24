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
package io.repsy.os.server.protocols.shared.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.RepoCreateRequest;
import io.repsy.os.generated.model.RepoListInfo;
import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The repositories of every type as one collection: list, count and create (RPS-1268).
 *
 * <p>None of these is a {@code @RepoOperation}: they name no repository and the repo type is a
 * query parameter or a body field, not a path variable, so {@code ProtocolAuthInterceptor} does not
 * see them and they authenticate like {@code /api/usages}, with a panel Bearer token only. Every
 * authenticated user may list and count, as the panel dashboard and repository list show them to
 * all roles; creating needs the ADMIN role.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
@NullMarked
@SuppressWarnings("java:S6856")
public class RepoCollectionController {

  private static final Set<String> SORT_PROPERTIES =
      Set.of("createdAt", "name", "type", "diskUsage");

  private final PanelAuthHelper panelAuthHelper;
  private final RepoTxService repoTxService;
  private final RestResponseFactory responseFactory;
  private final Map<RepoType, ProtocolApiFacade> apiFacadeMap;

  @GetMapping
  public RestResponse<PagedModel<RepoListInfo>> list(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestParam(required = false) final @Nullable RepoType type,
      @RequestParam(required = false) final @Nullable String q,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    this.panelAuthHelper.authenticate(authHeader);

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES);

    final var repos = this.repoTxService.listRepos(type, q, pageable);

    return this.responseFactory.success("reposFetched", new PagedModel<>(repos));
  }

  @GetMapping("/counts")
  public RestResponse<Map<RepoType, Long>> counts(
      @RequestHeader(AUTHORIZATION) final String authHeader) {

    this.panelAuthHelper.authenticate(authHeader);

    return this.responseFactory.success("repoCountsFetched", this.repoTxService.getRepoCounts());
  }

  @PostMapping
  public RestResponse<RepoListInfo> create(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestBody @Valid final RepoCreateRequest form) {

    final var user = this.panelAuthHelper.authenticate(authHeader);
    this.panelAuthHelper.requireAdmin(user);

    final var repoType = form.getType();

    final var repoInfo =
        this.repoTxService.createRepo(
            form.getName(),
            repoType,
            Boolean.TRUE.equals(form.getPrivateRepo()),
            form.getDescription());

    this.apiFacadeMap.get(repoType).createRepo(repoInfo.getStorageKey());

    return this.responseFactory.success(
        "repoCreated", this.repoTxService.getRepoListInfo(repoInfo.getStorageKey()));
  }
}
