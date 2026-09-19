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
package io.repsy.os.server.security.shared.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.os.shared.auth.utils.JwtUtils;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DockerScanTokenIssuer")
class DockerScanTokenIssuerTest {

  @Mock private JwtUtils jwtUtils;
  @InjectMocks private DockerScanTokenIssuer issuer;

  @Test
  @DisplayName("mints a five-minute token scoped to pulling the given repository")
  void mintsReadOnlyPullToken() {
    final var repoId = UUID.randomUUID();
    when(jwtUtils.createRepoScopedToken(eq(repoId), any(), any())).thenReturn("token");

    final var token = issuer.mintReadOnlyPullToken(repoId, "acme/app");

    assertThat(token).isEqualTo("token");
    verify(jwtUtils)
        .createRepoScopedToken(repoId, "repository:acme/app:pull", Duration.ofMinutes(5));
  }
}
