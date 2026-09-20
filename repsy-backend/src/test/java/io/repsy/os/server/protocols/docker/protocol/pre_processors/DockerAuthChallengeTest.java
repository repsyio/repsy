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
package io.repsy.os.server.protocols.docker.protocol.pre_processors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("DockerAuthChallenge")
class DockerAuthChallengeTest {

  @Test
  @DisplayName("points the client to the token endpoint of the host it called")
  void pointsToTokenEndpoint() {
    final var request = new MockHttpServletRequest("GET", "/v2/repo/app/manifests/latest");
    request.setScheme("https");
    request.setServerName("registry.example.com");
    request.setServerPort(443);

    assertThat(DockerAuthChallenge.of(request))
        .isEqualTo(
            "Bearer realm=\"https://registry.example.com/v2/token\","
                + "service=\"repsy\",scope=\"repository:*:pull\"");
  }
}
