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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.SecurityScansSummary;
import io.repsy.os.generated.model.VulnerabilityScanInfo;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.services.VulnerabilityScanTxService;
import io.repsy.os.server.security.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.shared.auth.PanelAuthHelper;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
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

  private final @NonNull PanelAuthHelper panelAuthHelper;
  private final @NonNull VulnerabilityScanTxService scanTxService;
  private final @NonNull VulnerabilityScannerRegistry scannerRegistry;
  private final @NonNull RestResponseFactory resp;

  @GetMapping("/scans")
  public @NonNull RestResponse<PagedModel<VulnerabilityScanInfo>> listScans(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestParam(required = false) final @Nullable Severity severity,
      @RequestParam(required = false) final @Nullable RepoType repoType,
      @RequestParam(required = false) final @Nullable String repoName,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "10") final int size) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    final var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    final var scans = this.scanTxService.listAllScans(severity, repoType, repoName, pageable);

    return this.resp.success("scansFetched", new PagedModel<>(scans));
  }

  /**
   * Deliberately ignores {@code severity} — unlike {@link #listScans}'s row filter, this reports
   * the distribution ACROSS severities, so filtering by one severity first would trivially show
   * 100% of that severity. {@code repoType}/{@code repoName} are kept since they narrow "which
   * scans" without collapsing the distribution itself.
   */
  @GetMapping("/scans/summary")
  public @NonNull RestResponse<SecurityScansSummary> getScansSummary(
      @RequestHeader(AUTHORIZATION) final @NonNull String authHeader,
      @RequestParam(required = false) final @Nullable RepoType repoType,
      @RequestParam(required = false) final @Nullable String repoName) {

    this.panelAuthHelper.requireAdmin(this.panelAuthHelper.authenticate(authHeader));

    final var summary = this.scanTxService.getSecurityScansSummary(repoType, repoName);

    return this.resp.success("scansSummaryFetched", summary);
  }

  /**
   * Single source of truth for "which repo types are scannable" — the frontend uses this to gate
   * scan-related UI (toggle, section, badges, filter options) so unsupported protocols never show
   * dead controls, and {@code ArtifactPushedEventPostProcessor} gates on the same registry directly.
   */
  @GetMapping("/supported-repo-types")
  public @NonNull RestResponse<List<String>> getSupportedRepoTypes() {
    return this.resp.success(
        "supportedRepoTypesFetched", List.copyOf(this.scannerRegistry.getSupportedRepoTypes()));
  }
}
