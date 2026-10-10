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
 * The three states of {@code repsy.security.auth-throttle.mode}. A new enforcement switch starts in
 * {@code observe} in Repsy Cloud; Repsy OS keeps {@link #ENFORCE} as its default, so the behaviour
 * of an existing installation does not change.
 */
public enum AuthThrottleMode {

  /** No client is ever tracked or refused. Equivalent to the former {@code enabled: false}. */
  OFF,

  /**
   * Failures are tracked exactly as in {@code enforce}, and {@link
   * AuthFailureThrottle#checkAllowed} still spends no BCrypt on a client that has used up its
   * window. The one difference: a client that would be refused is let through instead, once logged
   * at WARN and counted against {@link AuthFailureThrottle#WOULD_BLOCK_METRIC}, so the throttle can
   * be tuned against real traffic (NAT, shared CI egress IPs) before it refuses anything.
   */
  OBSERVE,

  /** A saturated client is refused with 429 (RPS-1092/RPS-1164). */
  ENFORCE
}
