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
package io.repsy.os.shared.auth.dtos;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.shared.constants.ErrorConstants;
import lombok.Getter;

@Getter
public enum AuthenticationType {
  DEPLOY_TOKEN("deploy_token"),
  USERNAME_PASSWORD("username_password"),
  DOCKER_SCAN("docker_scan"),
  /**
   * A JWT minted from a personal access token (the Docker token exchange). Its subject is the id of
   * the token, which is read again on every request, so it ends with the token. It is a protocol
   * token and never a panel one.
   */
  PERSONAL_ACCESS_TOKEN("personal_access_token"),
  ANONYMOUS("anonymous");

  final String value;

  AuthenticationType(final String value) {
    this.value = value;
  }

  public static AuthenticationType from(final String value) {
    if (DEPLOY_TOKEN.value.equals(value)) {
      return DEPLOY_TOKEN;
    }

    if (USERNAME_PASSWORD.value.equals(value)) {
      return USERNAME_PASSWORD;
    }

    if (DOCKER_SCAN.value.equals(value)) {
      return DOCKER_SCAN;
    }

    if (PERSONAL_ACCESS_TOKEN.value.equals(value)) {
      return PERSONAL_ACCESS_TOKEN;
    }

    if (ANONYMOUS.value.equals(value)) {
      return ANONYMOUS;
    }

    throw new BadRequestException(ErrorConstants.INVALID_AUTH_TYPE);
  }
}
