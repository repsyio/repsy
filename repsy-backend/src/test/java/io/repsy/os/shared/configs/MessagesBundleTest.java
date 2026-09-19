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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards against a msgId rendering as its raw key: {@code RestResponseFactory} falls back to the
 * msgId as {@code text} when messages.properties has no entry for it (RPS-940).
 */
@DisplayName("messages.properties")
class MessagesBundleTest {

  private static Properties messages;

  @BeforeAll
  static void loadMessages() throws IOException {
    messages = new Properties();

    try (InputStream in = MessagesBundleTest.class.getResourceAsStream("/messages.properties")) {
      messages.load(Objects.requireNonNull(in, "messages.properties is not on the classpath"));
    }
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("has a sentence for every msgId the panel APIs answer with")
  @ValueSource(
      strings = {
        // Users, profile and auth
        "userCreated",
        "userUpdated",
        "userDeleted",
        "usersFetched",
        "passwordReset",
        "profileFetched",
        "tokenRefreshed",
        "keyStoresFetched",
        // Security
        "artifactSecurityDetailFetched",
        "artifactSecuritySummaryFetched",
        "repoSecurityDetailFetched",
        "securitySummaryFetched",
        "versionSecuritySummaryFetched",
        "scanFindingsFetched",
        // Helm
        "chartsFetched",
        "chartDetailFetched",
        "chartTagsFetched",
        "chartVersionsFetched",
        "chartDeleted",
        // Cargo
        "cratesFetched",
        "crateFetched",
        "crateVersionsFetched",
        "crateVersionFetched",
        "crateDeleted",
        "crateVersionDeleted",
        // Ruby
        "gemsFetched",
        "gemVersionsFetched",
        "gemVersionFetched",
        "gemDeleted",
        "gemVersionDeleted",
        // Go
        "modulesFetched",
        "moduleInfoFetched",
        "moduleVersionsFetched",
        "moduleDeleted",
        "moduleVersionDeleted",
        "versionGone",
        // NuGet
        "nugetPackagesFetched",
        "nugetPackageFetched",
        "nugetVersionsFetched",
        "nugetVersionFetched",
        "nugetPackageDeleted",
        "nugetVersionDeleted",
        // PyPI
        "releasesFetched",
        "releaseDetailFetched"
      })
  void msgIdHasASentence(final String msgId) {
    assertThat(messages.getProperty(msgId)).isNotBlank().isNotEqualTo(msgId);
  }
}
