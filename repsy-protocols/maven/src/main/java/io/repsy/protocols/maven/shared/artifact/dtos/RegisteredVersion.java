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
package io.repsy.protocols.maven.shared.artifact.dtos;

import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A version of an artifact as the repository has registered it: what the artifact-level {@code
 * maven-metadata.xml} that is answered when none is stored lists (RPS-1369).
 *
 * @param versionName the version as registered ({@code 1.0-SNAPSHOT} for a snapshot, whatever its
 *     build)
 * @param lastUpdatedAt when the version was last registered or re-registered, or {@code null} when
 *     the repository recorded no time for it
 */
@NullMarked
public record RegisteredVersion(String versionName, @Nullable Instant lastUpdatedAt) {}
