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
package io.repsy.protocols.nuget.shared.packages.dtos;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public record NuGetVersionInfo(
    String packageId,
    String version,
    @Nullable String title,
    @Nullable String description,
    @Nullable String authors,
    @Nullable String tags,
    @Nullable String iconUrl,
    @Nullable String licenseUrl,
    @Nullable String projectUrl,
    boolean listed,
    long downloadCount,
    Instant publishedAt,
    @Nullable List<NuGetDependencyInfo> dependencies) {}
