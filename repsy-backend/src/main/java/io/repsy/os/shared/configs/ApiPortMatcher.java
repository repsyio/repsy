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
package io.repsy.os.shared.configs;

import io.repsy.libs.multiport.configs.props.MultiPortProperties;
import io.repsy.os.shared.utils.MultiPortNames;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Tells whether a request was served on the panel API port (see {@code MultiPortNames#PORT_API}),
 * including a TLS listener aliased to it, as opposed to the repository-serving (protocol) port.
 * {@link SecurityHeadersFilter} and {@link CorsGlobalConfiguration} scope their behaviour with it.
 */
@Component
@RequiredArgsConstructor
public class ApiPortMatcher {

  private final @NonNull MultiPortProperties multiPortProperties;

  public boolean isApiPort(final int localPort) {

    if (localPort == this.multiPortProperties.getPortFor(MultiPortNames.PORT_API)) {
      return true;
    }

    return MultiPortNames.PORT_API.equals(this.multiPortProperties.getPortAliases().get(localPort));
  }
}
