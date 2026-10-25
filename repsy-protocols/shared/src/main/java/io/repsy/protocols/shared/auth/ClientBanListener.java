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
package io.repsy.protocols.shared.auth;

/**
 * Hears of every ban a throttle starts (RPS-2324). A deployment with several instances implements
 * it to share bans: it stores the ban, and the other instances learn of it and call {@link
 * AuthFailureThrottle#adopt} or {@link SuspiciousRequestThrottle#adopt}. Repsy OS runs one instance
 * and has none; the throttles then keep their bans in memory only.
 *
 * <p>It is called on the request thread that tipped the ban over, once per ban, and not for a ban
 * that an instance adopted. It must be quick and must not throw; a throttle logs and ignores an
 * exception, as a store that is down must not fail a request.
 */
public interface ClientBanListener {

  /**
   * @param client the throttle key of the client, see {@link ClientKeys}
   * @param banSeconds how long the ban lasts from now
   */
  void banned(BanKind kind, String client, long banSeconds);
}
