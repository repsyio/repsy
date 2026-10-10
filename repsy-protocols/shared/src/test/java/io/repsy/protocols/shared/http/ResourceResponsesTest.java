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
package io.repsy.protocols.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@DisplayName("ResourceResponses")
class ResourceResponsesTest {

  @Test
  @DisplayName("an attachment names the file in quotes")
  void attachment() {
    assertThat(ResourceResponses.attachment("demo-1.0.jar"))
        .isEqualTo("attachment; filename=\"demo-1.0.jar\"");
  }

  @Test
  @DisplayName("a quote or a backslash in the name is escaped, so it cannot end the value")
  void attachmentEscapes() {
    assertThat(ResourceResponses.attachment("a\"b.tgz"))
        .isEqualTo("attachment; filename=\"a\\\"b.tgz\"");
    assertThat(ResourceResponses.attachment("a\\b.tgz"))
        .isEqualTo("attachment; filename=\"a\\\\b.tgz\"");
  }

  @Test
  @DisplayName("a name with a space is quoted whole")
  void attachmentWithSpace() {
    assertThat(ResourceResponses.attachment("my file.jar"))
        .isEqualTo("attachment; filename=\"my file.jar\"");
  }

  @Test
  @DisplayName("inline names nothing, or the file")
  void inline() {
    assertThat(ResourceResponses.inline()).isEqualTo("inline");
    assertThat(ResourceResponses.inline("demo.mod")).isEqualTo("inline; filename=\"demo.mod\"");
  }

  @Test
  @DisplayName("okAttachment is a 200 octet-stream with the attachment header")
  void okAttachment() {
    final var response = ResourceResponses.okAttachment("demo.whl").build();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"demo.whl\"");
  }
}
