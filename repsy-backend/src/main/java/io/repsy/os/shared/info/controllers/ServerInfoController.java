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

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.ServerBuildInfo;
import io.repsy.os.generated.model.ServerInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells which build of Repsy OS answers (RPS-2347): the release and the commit it was built from.
 * It needs no credential, so a client (the {@code rp} command line tool decides with it whether
 * this server is new enough for it) can ask before it signs in, and an instance too old for a
 * client still answers it.
 *
 * <p>The answer is a contract: {@code {"build": {"release": "...", "commit": "..."}}}, both members
 * always present and never empty. A build that has no build information (a plain IDE run, or an
 * image built without the build arguments) answers {@value #UNKNOWN} for the member it lacks. The
 * shape is ours, not Spring Boot's actuator document, so it does not move with a Boot upgrade.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/info")
@RequiredArgsConstructor
public class ServerInfoController {

  static final String UNKNOWN = "unknown";

  /** Absent when the jar carries no {@code META-INF/build-info.properties}. */
  private final ObjectProvider<BuildProperties> buildProperties;

  @GetMapping
  public ServerInfo get() {

    final var properties = this.buildProperties.getIfAvailable();

    return new ServerInfo()
        .build(
            new ServerBuildInfo()
                .release(valueOf(properties, "release"))
                .commit(valueOf(properties, "commit")));
  }

  private static String valueOf(final @Nullable BuildProperties properties, final String key) {

    final var value = properties == null ? null : properties.get(key);

    return StringUtils.hasText(value) ? value : UNKNOWN;
  }
}
