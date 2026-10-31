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
package io.repsy.os.server.protocols.maven.shared.storage.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import freemarker.cache.ClassTemplateLoader;
import freemarker.template.Configuration;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.storage.StorageStrategyRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-2372: the Maven directory browsing page prints each item's creation time in the server's
 * default time zone, to the minute. The page must look exactly as it did before the storage SPI
 * handed the time over as a {@link java.time.Instant}. Pinned in two default zones.
 */
@DisplayName("Maven directory listing page")
class MavenDirectoryListingTest {

  /** 2026-01-15T03:30:45.678Z: still the 14th at 22:30 in New York. */
  private static final Instant CREATED = Instant.parse("2026-01-15T03:30:45.678Z");

  private TimeZone originalZone;

  @BeforeEach
  void rememberZone() {
    this.originalZone = TimeZone.getDefault();
  }

  @AfterEach
  void restoreZone() {
    TimeZone.setDefault(this.originalZone);
  }

  private static Instant created() {
    return CREATED;
  }

  private static String render(final String zone) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone(zone));

    final var configuration = new Configuration(Configuration.VERSION_2_3_34);
    configuration.setTemplateLoader(
        new ClassTemplateLoader(MavenDirectoryListingTest.class.getClassLoader(), "/templates"));
    configuration.setDefaultEncoding("UTF-8");

    final var strategy = mock(StorageStrategy.class);
    final var registry = mock(StorageStrategyRegistry.class);
    when(registry.get(RepoType.MAVEN)).thenReturn(strategy);
    when(strategy.get(any(), any())).thenThrow(new IsADirectoryException());
    when(strategy.listDirectoryContents(any()))
        .thenReturn(
            new ArrayList<>(
                List.of(
                    StorageItemInfo.builder()
                        .name("com/")
                        .directory(true)
                        .createdAt(created())
                        .build(),
                    StorageItemInfo.builder()
                        .name("lib-1.0.jar")
                        .directory(false)
                        .size(123L)
                        .createdAt(created())
                        .build())));

    final var service = new MavenStorageService(registry, configuration);
    final var resource =
        service.getResource("maven-repo", StoragePath.of(java.util.UUID.randomUUID(), "com/acme/"));
    return new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("prints the creation time in the default zone, to the minute (New York)")
  void newYork() throws Exception {
    final var html = render("America/New_York");

    assertThat(html)
        .contains("<a href=\"com/\" title=\"com/\">com/</a>")
        .contains("2026-01-14 22:30")
        .doesNotContain("03:30")
        .contains("2026-01-14 22:30       123");
  }

  @Test
  @DisplayName("prints the creation time in the default zone, to the minute (UTC)")
  void utc() throws Exception {
    final var html = render("UTC");

    assertThat(html).contains("2026-01-15 03:30       123").doesNotContain("22:30");
  }
}
