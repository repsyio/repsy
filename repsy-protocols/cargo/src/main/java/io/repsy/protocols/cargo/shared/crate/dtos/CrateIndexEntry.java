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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrateIndexEntry(
    String name,
    String vers,
    List<CrateIndexDep> deps,
    String cksum,
    Map<String, List<String>> features,
    boolean yanked,
    @Nullable String links,
    int v,
    @JsonProperty("features2") @Nullable Map<String, List<String>> features2,
    @JsonProperty("rust_version") @Nullable String rustVersion) {}
