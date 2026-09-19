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
package io.repsy.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Full application context backed by the embedded H2 database and its Flyway migrations. */
@SpringBootTest(
    classes = RepsyApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
public abstract class H2IntegrationTest {

  private static final Path STORAGE_ROOT = createStorageRoot();

  @DynamicPropertySource
  static void registerH2Properties(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:h2:mem:rps957;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("storage-gateway.fs.base-path", STORAGE_ROOT::toString);
    registry.add("admin.initial-password", () -> "H2TestAdmin1!");
  }

  private static Path createStorageRoot() {
    try {
      return Files.createTempDirectory("repsy-rps957-h2");
    } catch (final IOException e) {
      throw new IllegalStateException("Could not create H2 test storage", e);
    }
  }
}
