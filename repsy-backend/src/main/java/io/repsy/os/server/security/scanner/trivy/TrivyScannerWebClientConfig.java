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
package io.repsy.os.server.security.scanner.trivy;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * The HTTP client Repsy uses to talk to the scanner.
 *
 * <p>{@code repsy.security.trivy.request-timeout-seconds} is not a bound on a whole request, since
 * an artifact of up to hundreds of megabytes is streamed to the scanner and that takes as long as
 * it takes. It bounds each wait instead: the {@code responseTimeout} runs from the moment the
 * request has been written in full until the scanner answers (and between the chunks of the
 * answer), and the write-idle timeout fails an upload the scanner has stopped reading for that
 * long. An upload that keeps moving is only bounded by {@code max-scan-duration-seconds}, see
 * {@link TrivyVulnerabilityScanner}.
 */
@Configuration
public class TrivyScannerWebClientConfig {

  private static final int CONNECT_TIMEOUT_MS = 5_000;
  private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

  @Bean
  public @NonNull WebClient trivyScannerWebClient(
      final @NonNull TrivyScannerProperties properties) {

    final var requestTimeout = Duration.ofSeconds(properties.requestTimeoutSeconds());

    final var httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
            .responseTimeout(requestTimeout)
            .doOnConnected(
                connection ->
                    connection.addHandlerLast(
                        new WriteTimeoutHandler(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)));

    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
        .build();
  }
}
