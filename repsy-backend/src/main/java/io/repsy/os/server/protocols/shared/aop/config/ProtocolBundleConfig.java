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
package io.repsy.os.server.protocols.shared.aop.config;

import io.repsy.os.server.protocols.shared.services.ProtocolApiFacade;
import io.repsy.os.server.shared.auth.ProtocolAuthService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@NullMarked
@Configuration
public class ProtocolBundleConfig {

  @Bean
  public Map<RepoType, ProtocolAuthService> protocolAuthServiceMap(
      @Qualifier("cargoAuthComponent") final ProtocolAuthService cargo,
      @Qualifier("mavenAuthComponent") final ProtocolAuthService maven,
      @Qualifier("npmAuthComponentImpl") final ProtocolAuthService npm,
      @Qualifier("dockerAuthComponent") final ProtocolAuthService docker,
      @Qualifier("pypiAuthComponent") final ProtocolAuthService pypi,
      @Qualifier("golangAuthComponent") final ProtocolAuthService golang,
      @Qualifier("helmAuthComponent") final ProtocolAuthService helm,
      @Qualifier("nuGetAuthComponent") final ProtocolAuthService nuget,
      @Qualifier("rubyAuthComponent") final ProtocolAuthService ruby) {

    final var map = new HashMap<RepoType, ProtocolAuthService>();
    map.put(RepoType.CARGO, cargo);
    map.put(RepoType.MAVEN, maven);
    map.put(RepoType.NPM, npm);
    map.put(RepoType.DOCKER, docker);
    map.put(RepoType.PYPI, pypi);
    map.put(RepoType.GOLANG, golang);
    map.put(RepoType.HELM, helm);
    map.put(RepoType.NUGET, nuget);
    map.put(RepoType.RUBY, ruby);
    return map;
  }

  @Bean
  public Map<RepoType, ProtocolApiFacade> protocolApiFacadeMap(
      @Qualifier("cargoApiFacade") final ProtocolApiFacade cargo,
      @Qualifier("mavenApiFacade") final ProtocolApiFacade maven,
      @Qualifier("npmApiFacade") final ProtocolApiFacade npm,
      @Qualifier("dockerApiFacade") final ProtocolApiFacade docker,
      @Qualifier("pypiApiFacade") final ProtocolApiFacade pypi,
      @Qualifier("golangApiFacade") final ProtocolApiFacade golang,
      @Qualifier("helmApiFacade") final ProtocolApiFacade helm,
      @Qualifier("nugetApiFacade") final ProtocolApiFacade nuget,
      @Qualifier("rubyApiFacade") final ProtocolApiFacade ruby) {

    final var map = new HashMap<RepoType, ProtocolApiFacade>();
    map.put(RepoType.CARGO, cargo);
    map.put(RepoType.MAVEN, maven);
    map.put(RepoType.NPM, npm);
    map.put(RepoType.DOCKER, docker);
    map.put(RepoType.PYPI, pypi);
    map.put(RepoType.GOLANG, golang);
    map.put(RepoType.HELM, helm);
    map.put(RepoType.NUGET, nuget);
    map.put(RepoType.RUBY, ruby);
    return map;
  }

  @Bean
  public ApplicationListener<ApplicationReadyEvent> protocolRegistryValidator(
      final Map<RepoType, ProtocolAuthService> protocolAuthServiceMap,
      final Map<RepoType, ProtocolApiFacade> protocolApiFacadeMap) {

    return event -> {
      assertComplete("protocolAuthServiceMap", protocolAuthServiceMap);
      assertComplete("protocolApiFacadeMap", protocolApiFacadeMap);
    };
  }

  private static void assertComplete(final String mapName, final Map<RepoType, ?> map) {
    final var missing =
        Arrays.stream(RepoType.values())
            .filter(type -> !map.containsKey(type))
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "ProtocolBundleConfig: "
              + mapName
              + " is missing entries for RepoType(s): "
              + missing
              + " — add the @Qualifier parameter and map.put() for each.");
    }
  }
}
