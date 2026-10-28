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
package io.repsy.os.shared.info.controllers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/info} (RPS-2347): open to everyone, the same two members in a fixed place. A
 * local build has no commit (the Maven default is {@code unknown}) but always a release.
 */
class ServerInfoControllerIT extends AbstractIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("answers the build without a credential")
  void answersWithoutACredential() throws Exception {
    this.mockMvc
        .perform(get("/api/info").with(apiPort()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.build.release").isNotEmpty())
        .andExpect(jsonPath("$.build.commit").isNotEmpty())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$.build.length()").value(2));
  }

  @Test
  @DisplayName("answers even with a token it would not accept, so an old client can still ask")
  void ignoresACredential() throws Exception {
    this.mockMvc
        .perform(get("/api/info").with(apiPort()).header(AUTHORIZATION, "Bearer not-a-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.build.release").isNotEmpty());
  }
}
