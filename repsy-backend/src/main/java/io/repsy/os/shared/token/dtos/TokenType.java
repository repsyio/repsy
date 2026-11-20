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
package io.repsy.os.shared.token.dtos;

import lombok.Getter;

@Getter
public enum TokenType {
  REPSY_DEPLOY_TOKEN("rdt-"),
  REPSY_USER_TOKEN("rut-"),
  /** The temporary token of a CLI login (RPS-2039), 24 hours; a personal access token row. */
  REPSY_CLI_TOKEN("rct-"),
  /**
   * The token a user grants a connected application on the consent page (RPS-2660), for example the
   * remote MCP gateway; a personal access token row of kind {@code CONNECTOR}. It is a panel API
   * credential only: the protocol entry points never recognize it (see {@link #isUserToken}).
   */
  REPSY_CONNECTOR_TOKEN("rat-");

  private final String prefix;

  TokenType(final String prefix) {
    this.prefix = prefix;
  }

  /** Whether {@code secret} carries this type's prefix, which is what tells the types apart. */
  public boolean matches(final String secret) {
    return secret.startsWith(this.prefix);
  }

  /**
   * Whether {@code secret} is a secret of the personal access token table: a long-lived {@link
   * #REPSY_USER_TOKEN} or a temporary {@link #REPSY_CLI_TOKEN}. Every credential entry point asks
   * this one question, so a token kind is recognized by all of them or by none, and a password that
   * looks like either is refused as a password.
   */
  public static boolean isUserToken(final String secret) {
    return REPSY_USER_TOKEN.matches(secret) || REPSY_CLI_TOKEN.matches(secret);
  }

  /**
   * Whether {@code secret} is a secret of the personal access token table that the panel API
   * accepts: a {@link #isUserToken user token} or a {@link #REPSY_CONNECTOR_TOKEN}. The panel entry
   * points ask this question; the protocol entry points ask {@link #isUserToken}, so a connector
   * token never authenticates a package manager (RPS-2660).
   */
  public static boolean isPanelToken(final String secret) {
    return isUserToken(secret) || REPSY_CONNECTOR_TOKEN.matches(secret);
  }
}
