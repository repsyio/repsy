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
package io.repsy.scanner.trivy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// maxPackages and maxBodyBytes are part of the POST /advisories contract (see the README) and are
// not exposed as environment variables; the others tune one installation.
@ConfigurationProperties(prefix = "scanner.advisories")
public record AdvisoryProperties(
    int maxPackages,
    long maxBodyBytes,
    long timeoutSeconds,
    int concurrency,
    long maxWaitSeconds) {}
