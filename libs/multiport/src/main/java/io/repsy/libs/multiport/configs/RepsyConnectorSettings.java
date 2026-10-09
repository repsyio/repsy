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
package io.repsy.libs.multiport.configs;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.tomcat.CompressionConnectorCustomizer;
import org.springframework.boot.web.server.Compression;

/**
 * The settings every listener of the application must have, whatever port or scheme it serves.
 *
 * <p>Spring Boot applies its connector customizers, and the {@code server.*} properties behind
 * them, to the primary connector only. A connector added with {@code addAdditionalConnectors} (the
 * plain ports of {@link TomcatMultiPortConfig} and any TLS listener an application adds itself)
 * starts bare, so it takes what it needs from here.
 */
public final class RepsyConnectorSettings {

  private RepsyConnectorSettings() {}

  /**
   * Applies the settings to {@code connector}.
   *
   * <ul>
   *   <li>Encoded slashes ({@code %2F}) are decoded instead of refused with a bodyless 400: npm
   *       addresses a scoped package as {@code @scope%2Fname}.
   *   <li>{@code connectionTimeout} is the idle timeout, in milliseconds.
   *   <li>{@code maxPartCount} is the upper bound on the number of parts a multipart request may
   *       carry ({@code server.tomcat.max-part-count}), a protection limit that must hold wherever
   *       a client can reach the application, not only on the connector Spring Boot customizes
   *       itself.
   *   <li>{@code compression}, when it is given, is applied like Spring Boot does for {@code
   *       server.compression.*} (nothing changes when it is not enabled). Pass {@code null} for a
   *       port that is not compressed.
   * </ul>
   */
  public static void apply(
      final @NonNull Connector connector,
      final int connectionTimeout,
      final int maxPartCount,
      final @Nullable Compression compression) {

    connector.setProperty("connectionTimeout", String.valueOf(connectionTimeout));
    connector.setEncodedSolidusHandling(EncodedSolidusHandling.DECODE.getValue());
    connector.setMaxPartCount(maxPartCount);

    if (compression != null) {
      new CompressionConnectorCustomizer(compression).customize(connector);
    }
  }
}
