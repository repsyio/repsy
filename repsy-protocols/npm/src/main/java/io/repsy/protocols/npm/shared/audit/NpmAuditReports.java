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
package io.repsy.protocols.npm.shared.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** The two audit reports as they are written on the wire. */
@UtilityClass
@NullMarked
public class NpmAuditReports {

  /**
   * One advisory of the bulk report ({@code POST /-/npm/v1/security/advisories/bulk}), which is a
   * list of them for each package name.
   */
  public record BulkAdvisory(
      long id,
      String url,
      String title,
      NpmSeverity severity,
      @JsonProperty("vulnerable_versions") String vulnerableVersions,
      List<String> cwe,
      Cvss cvss) {}

  /** The CVSS of an advisory; a score of 0 with no vector says that none is known. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cvss(double score, @Nullable String vectorString) {}

  /**
   * The legacy report ({@code POST /-/npm/v1/security/audits} and {@code /audits/quick}), which npm
   * 6, pnpm 9 and 10 read. {@code metadata} is always present, because pnpm and yarn classic cannot
   * read a report without it.
   */
  public record LegacyReport(
      List<Action> actions,
      Map<String, LegacyAdvisory> advisories,
      List<String> muted,
      Metadata metadata) {}

  /** The advice to look at one vulnerable package. */
  public record Action(
      String action,
      String module,
      @JsonProperty("isMajor") boolean major,
      List<Resolve> resolves) {}

  /** One place of the tree that an {@link Action} resolves. */
  public record Resolve(long id, String path, boolean dev, boolean optional, boolean bundled) {}

  /** The places of one vulnerable version in the tree. */
  public record Finding(
      String version, List<String> paths, boolean dev, boolean optional, boolean bundled) {}

  /** One advisory of the legacy report. */
  public record LegacyAdvisory(
      List<Finding> findings,
      long id,
      String created,
      String updated,
      boolean deleted,
      String title,
      @JsonProperty("found_by") Person foundBy,
      @JsonProperty("reported_by") Person reportedBy,
      @JsonProperty("module_name") String moduleName,
      List<String> cves,
      @JsonProperty("vulnerable_versions") String vulnerableVersions,
      @JsonProperty("patched_versions") String patchedVersions,
      String overview,
      String recommendation,
      String references,
      String access,
      NpmSeverity severity,
      String cwe,
      @JsonProperty("github_advisory_id") String githubAdvisoryId,
      AdvisoryMetadata metadata,
      String url) {}

  /** Who found or reported an advisory. */
  public record Person(String name) {}

  /** The extra facts npm shows about an advisory, which Repsy does not have. */
  public record AdvisoryMetadata(
      @JsonProperty("module_type") String moduleType,
      long exploitability,
      @JsonProperty("affected_components") String affectedComponents) {}

  /**
   * The totals of the legacy report. {@code vulnerabilities} counts advisories and has all five
   * severities.
   */
  public record Metadata(
      Map<String, Long> vulnerabilities,
      long dependencies,
      long devDependencies,
      long optionalDependencies,
      long totalDependencies) {}
}
