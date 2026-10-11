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
package io.repsy.os.server.security.scan.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

import io.repsy.core.web.paging.SortValidator;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.libs.scanner.VulnerabilityScannerRegistry;
import io.repsy.libs.scanner.dtos.Severity;
import io.repsy.os.generated.model.SecurityScansSummary;
import io.repsy.os.generated.model.VulnerabilityScanInfo;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/security")
@RequiredArgsConstructor
final class SecurityScanController {

  private static final Set<String> SCAN_SORT_PROPERTIES = Set.of("createdAt");

  private final PanelAuthHelper panelAuthHelper;
  private final VulnerabilityScanTxService scanTxService;
  private final VulnerabilityScannerRegistry scannerRegistry;

  @GetMapping("/scans")
  public ResponseEntity<PagedModel<VulnerabilityScanInfo>> listScans(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestParam(required = false) final @Nullable Severity severity,
      @RequestParam(required = false) final @Nullable RepoType repoType,
      @RequestParam(required = false) final @Nullable String repoName,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)
          final Pageable pageable) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    SortValidator.requireSortableBy(pageable, SCAN_SORT_PROPERTIES);

    final var scans = this.scanTxService.listAllScans(severity, repoType, repoName, pageable);

    return ResponseEntity.ok(new PagedModel<>(scans));
  }

  @GetMapping("/scans/summary")
  public ResponseEntity<SecurityScansSummary> getScansSummary(
      @RequestHeader(AUTHORIZATION) final String authHeader,
      @RequestParam(required = false) final @Nullable RepoType repoType,
      @RequestParam(required = false) final @Nullable String repoName) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    final var summary = this.scanTxService.getSecurityScansSummary(repoType, repoName);

    return ResponseEntity.ok(summary);
  }

  @GetMapping("/supported-repo-types")
  public ResponseEntity<List<String>> getSupportedRepoTypes() {
    return ResponseEntity.ok(List.copyOf(this.scannerRegistry.getSupportedRepoTypes()));
  }
}
