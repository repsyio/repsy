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
package io.repsy.os.shared.configs;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configures the {@code Content-Security-Policy} header {@code SecurityHeadersFilter} sends with
 * the panel and its static assets. See that filter for the built-in policy and which responses it
 * applies to.
 *
 * @param enabled whether the header is sent at all, {@code true} unless configured
 * @param reportOnly send {@code Content-Security-Policy-Report-Only} instead of the enforcing
 *     {@code Content-Security-Policy} header, so violations are only reported (in a browser that
 *     supports the reporting API and is told where to report) and nothing is actually blocked.
 *     Useful while rolling the policy out. {@code false} unless configured
 * @param policy overrides the built-in policy outright, so an operator can widen it (for example to
 *     allow another analytics or CDN host) without a rebuild. {@code null} unless configured, which
 *     keeps the built-in policy
 */
@ConfigurationProperties(prefix = "app.csp")
public record ContentSecurityPolicyProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("false") boolean reportOnly,
    @Nullable String policy) {}
