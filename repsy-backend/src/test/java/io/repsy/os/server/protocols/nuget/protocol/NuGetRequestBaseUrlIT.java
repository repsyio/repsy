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
package io.repsy.os.server.protocols.nuget.protocol;

import org.junit.jupiter.api.DisplayName;

/**
 * Without {@code repsy.nuget.public-url} the NuGet service index, registration and search name the
 * scheme, host and port of the request, as they did before RPS-1432. The suite runs with the
 * property unset, so it holds while `REPO_BASE_URL` is not exported to the JVM.
 */
@DisplayName("NuGet URLs with repsy.nuget.public-url unset")
class NuGetRequestBaseUrlIT extends AbstractNuGetBaseUrlIT {

  @Override
  String expectedRepoUrl(final String repoName) {
    return "http://internal.example:9090/" + repoName;
  }
}
