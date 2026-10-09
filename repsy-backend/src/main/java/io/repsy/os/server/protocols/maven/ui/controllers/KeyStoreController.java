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

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.AllowedKeyserverItem;
import io.repsy.os.generated.model.KeyStoreForm;
import io.repsy.os.generated.model.KeyStoreItem;
import io.repsy.os.generated.model.PgpPublicKeyForm;
import io.repsy.os.generated.model.PgpPublicKeyItem;
import io.repsy.os.server.protocols.maven.shared.keystore.services.KeyStoreService;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.http.ResponseEntities;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.os.shared.utils.SortValidator;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn")
@NullMarked
@SuppressWarnings("java:S6856")
public class KeyStoreController {

  private static final Set<String> SORT_PROPERTIES = Set.of("id", "host", "displayName");
  private static final Set<String> SORT_PROPERTIES_PUBLIC_KEYS =
      Set.of("id", "keyId", "fingerprint", "userId", "createdAt");

  private final KeyStoreService keyStoreService;
  private final PanelAuthHelper panelAuthHelper;

  @GetMapping("/allowed-key-servers")
  public ResponseEntity<List<AllowedKeyserverItem>> listAllowedServers(
      @RequestHeader(HttpHeaders.AUTHORIZATION) final String authHeader) {

    // A signed-in user of the current session, not just a token that verifies (RPS-1604).
    this.panelAuthHelper.authenticate(authHeader);

    return ResponseEntity.ok(this.keyStoreService.findAllActiveKeyservers());
  }

  @PostMapping("/key-stores/{repoName}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<KeyStoreItem> create(
      final RepoInfo repoInfo, @RequestBody @Valid final KeyStoreForm form) {

    final var item = this.keyStoreService.create(repoInfo, form);

    return ResponseEntities.created(this.location(repoInfo, "/{keyStoreId}", item.getId()), item);
  }

  @GetMapping("/key-stores/{repoName}/{keyStoreId}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<KeyStoreItem> get(
      final RepoInfo repoInfo, @PathVariable final UUID keyStoreId) {

    return ResponseEntity.ok(this.keyStoreService.get(repoInfo, keyStoreId));
  }

  @DeleteMapping("/key-stores/{repoName}/{keyStoreId}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<Void> delete(final RepoInfo repoInfo, @PathVariable final UUID keyStoreId) {

    this.keyStoreService.delete(repoInfo, keyStoreId);

    return ResponseEntities.noContent();
  }

  @GetMapping("/key-stores/{repoName}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<PagedModel<KeyStoreItem>> list(
      final RepoInfo repoInfo,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES);

    final var result = this.keyStoreService.findAll(repoInfo, pageable);

    return ResponseEntity.ok(new PagedModel<>(result));
  }

  @GetMapping("/key-stores/{repoName}/public-keys")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<PagedModel<PgpPublicKeyItem>> listPublicKeys(
      final RepoInfo repoInfo,
      @PageableDefault(sort = "id", direction = Sort.Direction.DESC) final Pageable pageable) {

    SortValidator.requireSortableBy(pageable, SORT_PROPERTIES_PUBLIC_KEYS);

    final var result = this.keyStoreService.findAllPublicKeys(repoInfo, pageable);

    return ResponseEntity.ok(new PagedModel<>(result));
  }

  @PostMapping("/key-stores/{repoName}/public-keys")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<PgpPublicKeyItem> createPublicKey(
      final RepoInfo repoInfo, @RequestBody @Valid final PgpPublicKeyForm form) {

    final var item = this.keyStoreService.createPublicKey(repoInfo, form);

    return ResponseEntities.created(
        this.location(repoInfo, "/public-keys/{publicKeyId}", item.getId()), item);
  }

  @GetMapping("/key-stores/{repoName}/public-keys/{publicKeyId}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<PgpPublicKeyItem> getPublicKey(
      final RepoInfo repoInfo, @PathVariable final UUID publicKeyId) {

    return ResponseEntity.ok(this.keyStoreService.getPublicKey(repoInfo, publicKeyId));
  }

  @DeleteMapping("/key-stores/{repoName}/public-keys/{publicKeyId}")
  @RepoOperation(scope = RepoScope.MAVEN, permission = Permission.MANAGE)
  public ResponseEntity<Void> deletePublicKey(
      final RepoInfo repoInfo, @PathVariable final UUID publicKeyId) {

    this.keyStoreService.deletePublicKey(repoInfo, publicKeyId);

    return ResponseEntities.noContent();
  }

  private URI location(final RepoInfo repoInfo, final String suffix, final UUID id) {

    return UriComponentsBuilder.fromPath("/api/mvn/key-stores/{repoName}" + suffix)
        .buildAndExpand(repoInfo.getName(), id)
        .encode()
        .toUri();
  }
}
