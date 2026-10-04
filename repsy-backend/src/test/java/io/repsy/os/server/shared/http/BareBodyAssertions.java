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
package io.repsy.os.server.shared.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Assertions for the success shape of the panel API (API guideline, Decision 5): a success body is
 * the bare resource with no {@code msgId} envelope, a create is 201 with a {@code Location} header,
 * an empty success is 204 with no body, and accepted work is 202 with a {@code Location}.
 */
@UtilityClass
public final class BareBodyAssertions {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void assertCreated(final MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNotBlank();
    assertNoMsgId(result);
  }

  public static void assertNoContent(final MvcResult result) {
    assertThat(result.getResponse().getStatus()).isEqualTo(204);
    assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
  }

  public static void assertAccepted(final MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNotBlank();
    assertNoMsgId(result);
  }

  /** A bare resource has no {@code msgId}, at the top level or in a list or page element. */
  public static void assertNoMsgId(final MvcResult result) throws Exception {
    final var content = result.getResponse().getContentAsString();

    if (content.isBlank()) {
      return;
    }

    assertThat(MAPPER.readTree(content).findValues("msgId"))
        .as("a success body is the bare resource, not the RestResponse envelope")
        .isEmpty();
  }
}
