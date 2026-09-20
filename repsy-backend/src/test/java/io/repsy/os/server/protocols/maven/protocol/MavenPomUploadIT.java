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
package io.repsy.os.server.protocols.maven.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * RPS-994: a POM that cannot be parsed used to be echoed back whole as the {@code msgId}, {@code
 * text} and {@code data} of a 500. It is the client's input that is malformed, so the answer is now
 * a 400 with the fixed {@code malformedPomFile} id and a readable sentence.
 */
@DisplayName("Maven POM upload")
class MavenPomUploadIT extends AbstractIntegrationTest {

  private static final String POM_PATH = "com/example/lib/1.0/lib-1.0.pom";
  private static final String MARKER = "reflected-marker-8f3a1c";

  private MvcResult uploadPom(final String repoName, final String pom) throws Exception {
    return this.mockMvc
        .perform(
            put("/{repo}/{path}", repoName, POM_PATH)
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_XML)
                .content(pom)
                .with(protocolPort()))
        .andReturn();
  }

  @Test
  @DisplayName("answers a malformed POM with 400 and a fixed msgId that does not echo the file")
  void malformedPomIsAFixedBadRequest() throws Exception {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("pom-mvn"));
    final var malformed =
        "<project><modelVersion>4.0.0</modelVersion><!-- " + MARKER + " --><groupId>";

    final var response = uploadPom(repo.getName(), malformed).getResponse();
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(body).doesNotContain(MARKER).doesNotContain("POM CONTENT");
    assertThat(JsonPath.<String>read(body, "$.msgId")).isEqualTo("malformedPomFile");
    assertThat(JsonPath.<String>read(body, "$.text")).contains("POM file is malformed");
    assertThat(JsonPath.<String>read(body, "$.data")).isEqualTo("malformedPomFile");
  }
}
