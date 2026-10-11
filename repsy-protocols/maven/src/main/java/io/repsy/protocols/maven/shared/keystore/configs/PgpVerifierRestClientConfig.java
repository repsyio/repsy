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
package io.repsy.protocols.maven.shared.keystore.configs;

import io.repsy.core.web.http.ResponseSizeLimitInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PgpVerifierRestClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_RESPONSE_BYTES = 512 * 1024;

  @Bean
  public RestClient pgpVerifierRestClient() {

    // HTTP/1.1 and no redirects, as the Reactor Netty client this replaced: the JDK client would
    // otherwise offer an h2c upgrade.
    final var httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    final var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(RESPONSE_TIMEOUT);

    return RestClient.builder()
        .requestFactory(requestFactory)
        .requestInterceptor(new ResponseSizeLimitInterceptor(MAX_RESPONSE_BYTES))
        .build();
  }
}
