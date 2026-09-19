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
package io.repsy.os.shared.auth.utils;

import lombok.Getter;

/**
 * The entry point a bearer JWT was issued for, carried in its {@code aud} claim. All JWTs are
 * signed with the same secret, so this is what stops a token issued for one purpose from
 * authenticating another.
 */
@Getter
public enum TokenRealm {

  /** The web UI API: access tokens from {@code /api/auth/login} and the profile endpoints. */
  PANEL("panel"),

  /** The package-manager endpoints: npm, Cargo, Docker and the other wire protocols. */
  PROTOCOL("protocol");

  private final String audience;

  TokenRealm(final String audience) {
    this.audience = audience;
  }
}
