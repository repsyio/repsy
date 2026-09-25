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
package io.repsy.protocols.npm.shared.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The {@code package} of a search object. {@code keywords}, {@code maintainers} and {@code links}
 * are always present because the npm client reads them without a check ({@code npm search} fails on
 * a package without a {@code maintainers} array).
 *
 * @param scope The scope, or {@code unscoped}
 * @param date The publication time of {@code version}, ISO-8601
 * @param publisher Who published it; Repsy keeps no publisher, so this is the first maintainer
 */
@NullMarked
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NpmSearchPackage(
    String name,
    String scope,
    String version,
    @Nullable String description,
    List<String> keywords,
    @Nullable String date,
    NpmSearchLinks links,
    @Nullable NpmSearchAuthor author,
    @Nullable NpmSearchPerson publisher,
    List<NpmSearchPerson> maintainers) {}
