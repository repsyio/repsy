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

import com.google.common.net.InetAddresses;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** How the throttles tell clients apart: by the remote address of the request. */
public final class ClientKeys {

  private ClientKeys() {}

  /** The throttle key of the client of the current request, or {@code null} outside a request. */
  public static @Nullable String currentClient() {

    final var remoteAddr = currentRemoteAddr();

    return remoteAddr == null ? null : throttleKey(remoteAddr);
  }

  public static @Nullable String currentRemoteAddr() {

    return RequestContextHolder.getRequestAttributes()
            instanceof final ServletRequestAttributes attributes
        ? attributes.getRequest().getRemoteAddr()
        : null;
  }

  /**
   * The throttle key for one remote address: the address itself for IPv4, or its {@code /64}
   * network for IPv6, so an attacker (or a legitimate NAT'd network) cannot evade the throttle by
   * cycling through the addresses of one network (RPS-1164). Parsing is strict (a literal address,
   * no DNS lookup); an address {@link InetAddresses#forString} cannot parse is throttled under its
   * own literal text, which only happens for a remote address Tomcat did not hand us as a literal
   * IP in the first place.
   */
  public static String throttleKey(final String remoteAddr) {

    final InetAddress address;

    try {
      address = InetAddresses.forString(remoteAddr);
    } catch (final IllegalArgumentException _) {
      return remoteAddr;
    }

    if (address instanceof final Inet6Address inet6) {
      final var network = Arrays.copyOf(inet6.getAddress(), 8);

      return HexFormat.of().formatHex(network);
    }

    return remoteAddr;
  }
}
