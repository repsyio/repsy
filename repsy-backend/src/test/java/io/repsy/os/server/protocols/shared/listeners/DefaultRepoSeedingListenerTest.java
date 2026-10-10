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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.shared.configs.DefaultRepoDefinitionsConfig;
import io.repsy.os.shared.repo.dtos.DefaultRepoDefinition;
import io.repsy.os.shared.repo.services.DefaultRepoSeeder;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.helm.shared.storage.services.HelmStorageService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.pypi.shared.storage.services.PypiStorageService;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins, per protocol, what a {@code UserCreatedEvent} makes the default-repository seeding do
 * (RPS-2062): the repository name, its {@link RepoType}, and that the storage directory is created
 * through that protocol's storage service. The definitions come from {@link
 * DefaultRepoDefinitionsConfig}, as in the application.
 */
@DisplayName("Default repository seeding per protocol")
class DefaultRepoSeedingListenerTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

  private final CargoStorageService cargo = mock(CargoStorageService.class);
  private final DockerStorageService docker = mock(DockerStorageService.class);
  private final GoStorageService go = mock(GoStorageService.class);
  private final HelmStorageService helm = mock(HelmStorageService.class);
  private final MavenStorageService maven = mock(MavenStorageService.class);
  private final NpmStorageService npm = mock(NpmStorageService.class);
  private final NuGetStorageService nuget = mock(NuGetStorageService.class);
  private final PypiStorageService pypi = mock(PypiStorageService.class);
  private final RubyStorageService ruby = mock(RubyStorageService.class);

  private DefaultRepoSeeder seeder;
  private DefaultRepoSeedingListener listener;

  @BeforeEach
  void setUp() {
    this.seeder = mock(DefaultRepoSeeder.class);
    final var config = new DefaultRepoDefinitionsConfig();
    final List<DefaultRepoDefinition> definitions =
        List.of(
            config.cargoDefaultRepo(this.cargo),
            config.dockerDefaultRepo(this.docker),
            config.goDefaultRepo(this.go),
            config.helmDefaultRepo(this.helm),
            config.mavenDefaultRepo(this.maven),
            config.npmDefaultRepo(this.npm),
            config.nugetDefaultRepo(this.nuget),
            config.pypiDefaultRepo(this.pypi),
            config.rubyDefaultRepo(this.ruby));
    this.listener = new DefaultRepoSeedingListener(this.seeder, definitions);
  }

  @SuppressWarnings("unchecked")
  private Consumer<UUID> seededCreator(final String name, final RepoType type) {
    final ArgumentCaptor<Consumer<UUID>> creator = ArgumentCaptor.forClass(Consumer.class);
    verify(this.seeder).seed(eq(name), eq(type), creator.capture());
    return creator.getValue();
  }

  private void publish() {
    this.listener.onUserCreated(new UserCreatedEvent<>(UUID.randomUUID(), "admin"));
  }

  @Test
  @DisplayName("seeds every protocol once under its name and creates storage through its service")
  void seedsEveryProtocol() {
    publish();

    seededCreator("cargo", RepoType.CARGO).accept(STORAGE_KEY);
    verify(this.cargo).createRepo(STORAGE_KEY);
    seededCreator("docker", RepoType.DOCKER).accept(STORAGE_KEY);
    verify(this.docker).createRepo(STORAGE_KEY);
    seededCreator("go", RepoType.GOLANG).accept(STORAGE_KEY);
    verify(this.go).createRepo(STORAGE_KEY);
    seededCreator("helm", RepoType.HELM).accept(STORAGE_KEY);
    verify(this.helm).createRepo(STORAGE_KEY);
    seededCreator("maven", RepoType.MAVEN).accept(STORAGE_KEY);
    verify(this.maven).createRepo(STORAGE_KEY);
    seededCreator("npm", RepoType.NPM).accept(STORAGE_KEY);
    verify(this.npm).createRepo(STORAGE_KEY);
    seededCreator("nuget", RepoType.NUGET).accept(STORAGE_KEY);
    verify(this.nuget).createRepo(STORAGE_KEY);
    seededCreator("pypi", RepoType.PYPI).accept(STORAGE_KEY);
    verify(this.pypi).createRepo(STORAGE_KEY);
    seededCreator("ruby", RepoType.RUBY).accept(STORAGE_KEY);
    verify(this.ruby).createRepo(STORAGE_KEY);
  }

  @Test
  @DisplayName("covers every RepoType exactly once")
  void coversEveryRepoType() {
    publish();

    final ArgumentCaptor<RepoType> types = ArgumentCaptor.forClass(RepoType.class);
    verify(this.seeder, org.mockito.Mockito.times(RepoType.values().length))
        .seed(any(), types.capture(), any());
    assertThat(types.getAllValues()).containsExactlyInAnyOrder(RepoType.values());
  }

  @Test
  @DisplayName("a protocol whose seeding fails does not keep the others from being seeded")
  void oneFailureDoesNotStopTheRest() {
    doThrow(new IllegalStateException("storage down"))
        .when(this.seeder)
        .seed(eq("docker"), eq(RepoType.DOCKER), any());

    publish();

    seededCreator("ruby", RepoType.RUBY);
    seededCreator("npm", RepoType.NPM);
  }
}
