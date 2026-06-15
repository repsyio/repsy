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
package io.repsy.os.config.ssl;

import io.repsy.os.config.ssl.RepsySslProperties.PortSslProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@NullMarked
@Component
@RequiredArgsConstructor
public class SslConfigurationValidator implements ApplicationRunner {

  private static final String FILE_PREFIX = "file:";

  private final RepsySslProperties sslProperties;

  @Override
  public void run(final ApplicationArguments args) {
    this.validate(this.sslProperties.api(), "api", "API_SSL_KEY_STORE_PATH");
    this.validate(this.sslProperties.repo(), "repo", "REPO_SSL_KEY_STORE_PATH");
  }

  private void validate(
      final PortSslProperties props, final String portName, final String envVarName) {

    if (!props.enabled()) {
      log.info("SSL disabled for {} port", portName);
      return;
    }

    if (!StringUtils.hasText(props.keyStore())) {
      throw new IllegalStateException(
          "SSL is enabled for %s port but %s is not configured. ".formatted(portName, envVarName)
              + "Set the %s environment variable to the keystore path.".formatted(envVarName));
    }

    if (props.keyStore().startsWith(FILE_PREFIX)) {
      this.validateFilePath(props.keyStore().substring(FILE_PREFIX.length()));
    }

    log.info(
        "SSL enabled for {} port on :{} using key store: {}",
        portName,
        props.port(),
        props.keyStore());
  }

  private void validateFilePath(final String rawPath) {
    final var path = Path.of(rawPath);
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      throw new IllegalStateException(
          "SSL key store file not found or is not a regular file: " + rawPath);
    }
  }
}
