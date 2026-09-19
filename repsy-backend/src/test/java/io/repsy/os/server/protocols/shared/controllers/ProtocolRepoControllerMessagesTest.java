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
package io.repsy.os.server.protocols.shared.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards against a msgId of {@link ProtocolRepoController} rendering as its raw key: {@code
 * RestResponseFactory} falls back to the msgId as {@code text} when messages.properties has no
 * entry for it (RPS-907).
 */
@DisplayName("ProtocolRepoController messages")
class ProtocolRepoControllerMessagesTest {

  /** Surefire runs with the module directory as the working directory. */
  private static final Path CONTROLLER_SOURCE =
      Path.of(
          "src/main/java/io/repsy/os/server/protocols/shared/controllers/"
              + "ProtocolRepoController.java");

  private static final Pattern RESPONSE_MSG_ID =
      Pattern.compile("responseFactory\\.(?:success|warning|error)\\(\\s*\"(\\w+)\"");

  /** Error msgIds the controller's routes return from the shared interceptor and resolvers. */
  private static final Set<String> INTERCEPTOR_MSG_IDS = Set.of("repoTypeNotFound");

  private static Properties messages;

  @BeforeAll
  static void loadMessages() throws IOException {
    messages = new Properties();

    try (InputStream in =
        ProtocolRepoControllerMessagesTest.class.getResourceAsStream("/messages.properties")) {
      messages.load(Objects.requireNonNull(in, "messages.properties is not on the classpath"));
    }
  }

  @Test
  @DisplayName("every msgId the controller returns has an entry in messages.properties")
  void controllerMsgIdsHaveMessages() throws IOException {
    final var source = Files.readString(CONTROLLER_SOURCE);
    final var msgIds = new TreeSet<String>();
    final var matcher = RESPONSE_MSG_ID.matcher(source);

    while (matcher.find()) {
      msgIds.add(matcher.group(1));
    }

    // Guards the scan itself: a refactor that breaks the pattern must not make this test vacuous.
    assertThat(msgIds).contains("repoCreated", "repoRenamed", "repoTypeFetched");
    assertThat(msgIds).allSatisfy(msgId -> assertThat(messages).containsKey(msgId));
  }

  @Test
  @DisplayName("every interceptor msgId on the controller's routes has an entry")
  void interceptorMsgIdsHaveMessages() {
    assertThat(INTERCEPTOR_MSG_IDS).allSatisfy(msgId -> assertThat(messages).containsKey(msgId));
  }
}
