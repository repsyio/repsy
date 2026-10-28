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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

class ServerInfoControllerTest {

  private static ServerInfoController controllerWith(final Properties buildInfo) {

    final var beans = new StaticListableBeanFactory();

    if (buildInfo != null) {
      beans.addBean("buildProperties", new BuildProperties(buildInfo));
    }

    final ObjectProvider<BuildProperties> provider = beans.getBeanProvider(BuildProperties.class);

    return new ServerInfoController(provider);
  }

  private static Properties properties(final String release, final String commit) {

    final var properties = new Properties();

    if (release != null) {
      properties.setProperty("release", release);
    }

    if (commit != null) {
      properties.setProperty("commit", commit);
    }

    return properties;
  }

  @Test
  @DisplayName("answers the release and the commit of the build")
  void answersTheBuild() {
    final var sha = "0123456789abcdef0123456789abcdef01234567";

    final var info = controllerWith(properties("26.10.0", sha)).get();

    assertThat(info.getBuild().getRelease()).isEqualTo("26.10.0");
    assertThat(info.getBuild().getCommit()).isEqualTo(sha);
  }

  @Test
  @DisplayName("answers unknown for both members when the build has no build information")
  void answersUnknownWithoutBuildInformation() {
    final var info = controllerWith(null).get();

    assertThat(info.getBuild().getRelease()).isEqualTo(ServerInfoController.UNKNOWN);
    assertThat(info.getBuild().getCommit()).isEqualTo(ServerInfoController.UNKNOWN);
  }

  @Test
  @DisplayName("answers unknown for a member that is missing or blank, never an empty string")
  void answersUnknownForAMissingOrBlankMember() {
    final var info = controllerWith(properties("  ", null)).get();

    assertThat(info.getBuild().getRelease()).isEqualTo(ServerInfoController.UNKNOWN);
    assertThat(info.getBuild().getCommit()).isEqualTo(ServerInfoController.UNKNOWN);
  }
}
