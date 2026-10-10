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
package io.repsy.os.server.protocols.shared.listeners;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.server.protocols.cargo.shared.listeners.CargoAuthListener;
import io.repsy.os.server.protocols.docker.shared.listeners.DockerAuthListener;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.golang.shared.listeners.GoAuthListener;
import io.repsy.os.server.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.os.server.protocols.helm.shared.listeners.HelmAuthListener;
import io.repsy.os.server.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.os.server.protocols.maven.shared.listeners.MavenAuthListener;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.server.protocols.npm.shared.listeners.NpmAuthListener;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.server.protocols.nuget.shared.listeners.NuGetAuthListener;
import io.repsy.os.server.protocols.pypi.shared.listeners.PypiAuthListener;
import io.repsy.os.server.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.os.server.protocols.ruby.shared.listeners.RubyAuthListener;
import io.repsy.os.server.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.os.shared.repo.services.DefaultRepoSeeder;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins, per protocol, what a {@code UserCreatedEvent} makes the default-repository seeding do
 * (RPS-2062): the repository name, its {@link RepoType}, and that the storage directory is created
 * through that protocol's storage service. It is the safety net for replacing the per-protocol
 * {@code *AuthListener}s with one listener.
 */
@DisplayName("Default repository seeding per protocol")
class DefaultRepoSeedingListenerTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  @SuppressWarnings("unchecked")
  private static Consumer<UUID> seededCreator(
      final DefaultRepoSeeder seeder, final String name, final RepoType type) {
    final ArgumentCaptor<Consumer<UUID>> creator = ArgumentCaptor.forClass(Consumer.class);
    verify(seeder).seed(eq(name), eq(type), creator.capture());
    return creator.getValue();
  }

  @Test
  @DisplayName("Cargo seeds 'cargo' through CargoStorageService")
  void cargo() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(CargoStorageService.class);

    new CargoAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "cargo", RepoType.CARGO).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Docker seeds 'docker' through DockerStorageService")
  void docker() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(DockerStorageService.class);

    new DockerAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "docker", RepoType.DOCKER).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Go seeds 'go' as GOLANG through GoStorageService")
  void golang() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(GoStorageService.class);

    new GoAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "go", RepoType.GOLANG).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Helm seeds 'helm' through HelmStorageService")
  void helm() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(HelmStorageService.class);

    new HelmAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "helm", RepoType.HELM).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Maven seeds 'maven' through MavenStorageService")
  void maven() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(MavenStorageService.class);

    new MavenAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "maven", RepoType.MAVEN).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Npm seeds 'npm' through NpmStorageService")
  void npm() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(NpmStorageService.class);

    new NpmAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "npm", RepoType.NPM).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("NuGet seeds 'nuget' through NuGetStorageService")
  void nuget() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(NuGetStorageService.class);

    new NuGetAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "nuget", RepoType.NUGET).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Pypi seeds 'pypi' through PypiStorageService")
  void pypi() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(PypiStorageService.class);

    new PypiAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "pypi", RepoType.PYPI).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("Ruby seeds 'ruby' through RubyStorageService")
  void ruby() {
    final var seeder = mock(DefaultRepoSeeder.class);
    final var storage = mock(RubyStorageService.class);

    new RubyAuthListener(seeder, storage).onRegistrationCompleted(event());

    seededCreator(seeder, "ruby", RepoType.RUBY).accept(STORAGE_KEY);
    verify(storage).createRepo(STORAGE_KEY);
  }

  private static UserCreatedEvent<UUID> event() {
    return new UserCreatedEvent<>(UUID.randomUUID(), "admin");
  }
}
