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

/**
 * The kind of a row of the personal access token table (RPS-2039, RPS-2660). Every kind is
 * authenticated by the same lookup; they differ in who creates them, where they are accepted and
 * how they are managed.
 */
public enum PersonalAccessTokenKind {
  /**
   * The long-lived token a user creates under Settings (or with {@code rp token create}), for CI.
   * It is listed, revocable by its owner and counts against the cap.
   */
  PERSONAL,
  /**
   * The 24-hour token the authorize screen hands to the Repsy CLI. It is never listed, never counts
   * against the cap, and a newer one replaces the previous one of the same user.
   */
  TEMPORARY,
  /**
   * The token a user grants a connected application, such as the remote MCP gateway, on the consent
   * page (RPS-2660). It is accepted on the panel API only, never on a protocol route; it cannot
   * manage tokens; it lives 90 days from its last use, at most 365 days from its creation; it is
   * listed apart from the personal tokens and does not count against their cap.
   */
  CONNECTOR
}
