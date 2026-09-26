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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configures the opt-in {@code Strict-Transport-Security} header {@link SecurityHeadersFilter} can
 * send (RPS-1514).
 *
 * <p>HSTS is scoped to a host, not to a port. An operator who serves the panel on plain {@code
 * :8080} and TLS on {@code :8443} of one host (the documented setup) would have browsers upgrade
 * {@code http://host:8080} to HTTPS and lose the panel, so the header is off unless an operator who
 * knows their deployment is TLS-only turns it on. A reverse proxy normally owns HSTS.
 *
 * @param hstsMaxAge {@code max-age} in seconds ({@code app.hsts-max-age}, env {@code
 *     APP_HSTS_MAX_AGE}). {@code 0} (the default) or a negative value never sends the header. When
 *     positive it is sent only on a secure request (TLS, or a proxy that forwarded {@code
 *     X-Forwarded-Proto: https}), never on plain HTTP
 */
@ConfigurationProperties(prefix = "app")
public record AppHstsProperties(@DefaultValue("0") long hstsMaxAge) {

  /** Returns whether the header is configured at all. */
  public boolean enabled() {

    return this.hstsMaxAge > 0;
  }

  /** Returns the header value, e.g. {@code max-age=31536000}. */
  public String headerValue() {

    return "max-age=" + this.hstsMaxAge;
  }
}
