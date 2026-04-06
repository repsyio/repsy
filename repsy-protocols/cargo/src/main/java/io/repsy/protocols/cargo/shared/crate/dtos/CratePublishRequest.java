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
package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@JsonIgnoreProperties(ignoreUnknown = true)
public record CratePublishRequest(
    String name,
    String vers,
    @Nullable List<CratePublishDep> deps,
    @Nullable Map<String, List<String>> features,
    @Nullable List<String> authors,
    @Nullable String description,
    @Nullable String documentation,
    @Nullable String homepage,
    @Nullable String readme,
    @JsonProperty("readme_file") @Nullable String readmeFile,
    @Nullable List<String> keywords,
    @Nullable List<String> categories,
    @Nullable String license,
    @JsonProperty("license_file") @Nullable String licenseFile,
    @Nullable String repository,
    @Nullable String links,
    @JsonProperty("rust_version") @Nullable String rustVersion,
    @Nullable String cksum,
    @JsonProperty("features2") @Nullable Map<String, List<String>> features2) {}
