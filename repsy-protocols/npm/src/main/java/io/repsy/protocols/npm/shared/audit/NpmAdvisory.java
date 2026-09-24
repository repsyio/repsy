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

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One vulnerability of one package, in the shape both audit reports are built from.
 *
 * @param id A number that is stable for the vulnerability and the package, and a safe integer in
 *     JavaScript
 * @param packageName The name of the vulnerable package, with its scope
 * @param vulnerableVersions The versions of the package that are known to be vulnerable, as exact
 *     versions
 * @param patchedVersions The range of the versions that fix it (such as {@code >=1.2.3}), or {@code
 *     null} if no fix is known
 * @param cves The CVE ids that name the vulnerability, if it has any
 * @param githubAdvisoryId The GitHub advisory id, if the vulnerability has one
 * @param references A link to more information, or an empty string
 * @param updated When the scan that found it finished
 * @param reportedBy The scanner that found it
 */
@NullMarked
public record NpmAdvisory(
    long id,
    String packageName,
    String title,
    String url,
    NpmSeverity severity,
    List<String> vulnerableVersions,
    @Nullable String patchedVersions,
    List<String> cves,
    @Nullable String githubAdvisoryId,
    String overview,
    String recommendation,
    String references,
    @Nullable Double cvssScore,
    @Nullable String cvssVector,
    Instant updated,
    @Nullable String reportedBy) {}
