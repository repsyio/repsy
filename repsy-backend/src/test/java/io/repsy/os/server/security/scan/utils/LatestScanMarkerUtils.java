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
package io.repsy.os.server.security.scan.utils;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recomputes the latest-scan markers and the finding counts (RPS-2115, V0037) of every scan of a
 * repo, the way the V0038 backfill does. The application keeps them right when it creates,
 * completes or fails a scan; a test that inserts scans or findings itself (with a chosen {@code
 * createdAt}) calls this once its rows are flushed, so the panel queries see what they would see in
 * production.
 */
public final class LatestScanMarkerUtils {

  private static final String REFRESH =
      """
      update "public"."vulnerability_scan" s
      set "superseded_at" = (
              select min(n."created_at")
              from "public"."vulnerability_scan" n
              where n."repo_id" = s."repo_id"
                and n."artifact_name" = s."artifact_name"
                and n."artifact_version" = s."artifact_version"
                and (n."created_at" > s."created_at"
                  or (n."created_at" = s."created_at" and n."id" > s."id"))),
          "completed_superseded_at" = case when s."status" = 'COMPLETED' then (
              select min(coalesce(n."completed_at", n."created_at"))
              from "public"."vulnerability_scan" n
              where n."repo_id" = s."repo_id"
                and n."artifact_name" = s."artifact_name"
                and n."artifact_version" = s."artifact_version"
                and n."status" = 'COMPLETED'
                and (n."created_at" > s."created_at"
                  or (n."created_at" = s."created_at" and n."id" > s."id"))) end,
          "finding_count" = case when s."status" = 'COMPLETED' then (
              select count(*)
              from "public"."vulnerability_finding" f
              where f."scan_id" = s."id") end
      where s."repo_id" = (select "repo_id" from "public"."vulnerability_scan" where "id" = ?)""";

  private LatestScanMarkerUtils() {}

  /** Refreshes every scan of the repo of {@code scanId}; flush the persistence context first. */
  public static void refreshRepoOf(final JdbcTemplate jdbc, final UUID scanId) {
    jdbc.update(REFRESH, scanId);
  }
}
