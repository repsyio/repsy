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
package io.repsy.protocols.nuget.shared.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;

/**
 * Names the address a NuGet client reaches a repo at, {@code <address>/<repoName>}, which the
 * service index, the registration and the search responses build every URL they advertise from
 * (RPS-1432). The application decides where the address comes from; {@link
 * NuGetUrlBuilder#buildBaseUrl} is the request-derived default.
 */
@NullMarked
@FunctionalInterface
public interface NuGetBaseUrlResolver {

  /** The URL of the repo, without a trailing slash. */
  String baseUrl(HttpServletRequest request, String repoName);
}
