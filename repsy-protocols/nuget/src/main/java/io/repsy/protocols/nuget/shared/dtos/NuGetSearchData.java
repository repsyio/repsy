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
package io.repsy.protocols.nuget.shared.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Search result entry per NuGet Search Query Service spec. */
@NullMarked
@JsonInclude(Include.NON_NULL)
public record NuGetSearchData(
    @JsonProperty("@id") String id,
    @JsonProperty("@type") String type,
    String registration,
    @JsonProperty("id") String packageId,
    String version,
    @Nullable String description,
    @Nullable String summary,
    @Nullable String title,
    @Nullable String iconUrl,
    @Nullable String licenseUrl,
    @Nullable String projectUrl,
    @Nullable List<String> tags,
    @Nullable List<String> authors,
    long totalDownloads,
    boolean verified,
    List<NuGetSearchVersionData> versions) {}
