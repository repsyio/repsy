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
package io.repsy.libs.multiport.configs.props;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@ConfigurationProperties("multiport")
@Configuration
public class MultiPortProperties {

  private static final String DEFAULT_MAIN_PORT = "8080";
  private static final int DEFAULT_CONNECTION_TIMEOUT = 12_000;

  private String mainPort = DEFAULT_MAIN_PORT;
  private Map<String, Integer> ports;
  private TomcatProperties tomcat = new TomcatProperties();

  public int getPortFor(final @NonNull String logicalName) {

    return this.ports.get(logicalName);
  }

  public @NonNull Map<String, Integer> getAdditionalPorts() {

    return this.ports.entrySet().stream()
        .filter(e -> !e.getValue().equals(Integer.parseInt(this.mainPort)))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public void validatePorts() {

    for (final var entry : this.ports.entrySet()) {

      final var name = entry.getKey();
      final var port = entry.getValue();

      this.checkPort(name, port, Integer.parseInt(this.mainPort));
    }
  }

  private void checkPort(final @NonNull String name, final int port, final int mainPort) {

    if (port == Integer.parseInt(this.mainPort)) {
      throw new IllegalStateException(
          "Port %s (%d) cannot equal main-port (%d)".formatted(name, port, mainPort));
    }
  }

  @Data
  private static final class TomcatProperties {

    private int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
  }
}
