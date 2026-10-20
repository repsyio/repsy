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
package io.repsy.os.shared.user.contracts;

/**
 * Lets the user module require an administrator without depending on the auth module: {@code
 * PanelAuthHelper} in {@code shared.auth} implements it (RPS-2278).
 */
public interface AdminAuthenticator {

  /**
   * Authenticates the web UI request with the access token of a login and requires the user to be
   * an ADMIN.
   */
  void authenticateAdmin(String authHeader);
}
