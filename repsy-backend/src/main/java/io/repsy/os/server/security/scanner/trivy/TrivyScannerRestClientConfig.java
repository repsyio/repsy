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

import io.repsy.os.shared.http.ResponseSizeLimitInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP clients Repsy uses to talk to the scanner.
 *
 * <p>{@code repsy.security.trivy.request-timeout-seconds} is not a bound on the artifact upload,
 * since an artifact of up to hundreds of megabytes is streamed to the scanner and that takes as
 * long as it takes. The JDK HTTP client has one timeout, from the start of the request until the
 * response headers arrive (so an upload counts), and no write-idle or per-chunk read timeout. Two
 * clients are therefore configured:
 *
 * <ul>
 *   <li>{@code trivyScannerRestClient} for the small status and advisory calls, bounded by {@code
 *       request-timeout-seconds}.
 *   <li>{@code trivyScannerUploadRestClient} for the artifact upload, bounded by {@code
 *       max-scan-duration-seconds}: the call as a whole is capped there, after which the status
 *       poller fails the scan anyway, see {@link TrivyVulnerabilityScanner}.
 * </ul>
 */
@Configuration
public class TrivyScannerRestClientConfig {

  private static final @NonNull Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

  @Bean
  public @NonNull RestClient trivyScannerRestClient(
      final @NonNull TrivyScannerProperties properties) {

    return build(Duration.ofSeconds(properties.requestTimeoutSeconds()))
        .requestInterceptor(new ResponseSizeLimitInterceptor(MAX_RESPONSE_BYTES))
        .build();
  }

  @Bean
  public @NonNull RestClient trivyScannerUploadRestClient(
      final @NonNull TrivyScannerProperties properties) {

    // No interceptor here: any interceptor makes RestClient buffer the whole request body, and the
    // artifact must be streamed. The answer is bodiless, so there is nothing to cap either.
    return build(Duration.ofSeconds(properties.maxScanDurationSeconds())).build();
  }

  private static RestClient.@NonNull Builder build(final @NonNull Duration requestTimeout) {

    // HTTP/1.1 and no redirects, as the Reactor Netty client this replaced.
    final var httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    final var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(requestTimeout);

    return RestClient.builder().requestFactory(requestFactory);
  }
}
