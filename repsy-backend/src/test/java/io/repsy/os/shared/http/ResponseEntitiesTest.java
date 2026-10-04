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
package io.repsy.os.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class ResponseEntitiesTest {

  private static final URI LOCATION = URI.create("/api/repos/my-repo");

  @Test
  @DisplayName("created is 201 with the Location header and the bare body")
  void created() {
    final var response = ResponseEntities.created(LOCATION, "body");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation()).isEqualTo(LOCATION);
    assertThat(response.getBody()).isEqualTo("body");
  }

  @Test
  @DisplayName("noContent is 204 with no body and no Location")
  void noContent() {
    final var response = ResponseEntities.noContent();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getBody()).isNull();
    assertThat(response.getHeaders().containsHeader(HttpHeaders.LOCATION)).isFalse();
  }

  @Test
  @DisplayName("accepted is 202 with the status Location and no body")
  void accepted() {
    final var response = ResponseEntities.accepted(LOCATION);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation()).isEqualTo(LOCATION);
    assertThat(response.getBody()).isNull();
  }
}
