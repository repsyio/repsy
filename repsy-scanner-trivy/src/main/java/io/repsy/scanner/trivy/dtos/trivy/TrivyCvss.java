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
package io.repsy.scanner.trivy.dtos.trivy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** One vendor's CVSS entry (e.g. "nvd", "redhat", "ghsa") from Trivy's multi-source CVSS map. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrivyCvss(
    @JsonProperty("V2Vector") @Nullable String v2Vector,
    @JsonProperty("V3Vector") @Nullable String v3Vector,
    @JsonProperty("V2Score") @Nullable Double v2Score,
    @JsonProperty("V3Score") @Nullable Double v3Score) {}
