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
package io.repsy.os.shared.search;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.golang.shared.go_module.repositories.GoModuleRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.pypi.shared.python_package.repositories.PypiPackageRepository;
import io.repsy.os.shared.user.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The contains-searches on embedded H2, where the rewritten JPQL is a plain LIKE (RPS-2117). */
@DisplayName("Contains searches are case-insensitive and literal on embedded H2 (RPS-2117)")
class H2TrigramSearchIT extends H2IntegrationTest {

  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PypiPackageRepository pypiPackageRepository;
  @Autowired private GoModuleRepository goModuleRepository;
  @Autowired private UserRepository userRepository;
  @PersistenceContext private EntityManager entityManager;

  private TrigramSearchFixture fixture;

  @BeforeEach
  void createFixture() {
    this.fixture =
        new TrigramSearchFixture(
            this.entityManager,
            this.artifactRepository,
            this.npmPackageRepository,
            this.pypiPackageRepository,
            this.goModuleRepository,
            this.userRepository);
  }

  @Test
  @DisplayName("Maven")
  void maven() {
    this.fixture.assertMavenSearch();
  }

  @Test
  @DisplayName("npm")
  void npm() {
    this.fixture.assertNpmSearch();
  }

  @Test
  @DisplayName("PyPI")
  void pypi() {
    this.fixture.assertPypiSearch();
  }

  @Test
  @DisplayName("Go")
  void go() {
    this.fixture.assertGoSearch();
  }

  @Test
  @DisplayName("users")
  void users() {
    this.fixture.assertUserSearch();
  }
}
