///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

/**
 * The panel API models the UI stubs (`security-stubs.ts`) answer with (RPS-1638, H9). One module, so that
 * the model is a per-target import: the stubs are typed by the generated client of Repsy OS
 * (`src/api/generated`, from `openapi-spec.yaml`), and a consumer whose panel API differs (Repsy Cloud, with
 * its own generated client) replaces THIS file, not every stub. Nothing here is behaviour: it only
 * re-exports the values (the enums) and types the stubs use.
 */
export {
  FixStatus,
  RepoType,
  ResponseType,
  ScanStatus,
  Severity,
  type PagedModelVulnerabilityFindingInfo,
  type PagedModelVulnerabilityScanInfo,
  type RecentScannedVersion,
  type RepoSecurityDetail,
  type RepoSecuritySummary,
  type RestResponseListString,
  type RestResponsePagedModelVulnerabilityFindingInfo,
  type RestResponsePagedModelVulnerabilityScanInfo,
  type RestResponseRepoSecurityDetail,
  type RestResponseScanOverview,
  type RestResponseSecurityScansSummary,
  type RestResponseSecuritySummary,
  type RestResponseVersionSecuritySummaryMap,
  type RestResponseVulnerabilityScanDetail,
  type RestResponseVulnerabilityScanInfo,
  type ScanOverview,
  type SecurityScansSummary,
  type VersionSecuritySummary,
  type VulnerabilityFindingInfo,
  type VulnerabilityScanDetail,
  type VulnerabilityScanInfo,
} from '../api/generated/index.js';
