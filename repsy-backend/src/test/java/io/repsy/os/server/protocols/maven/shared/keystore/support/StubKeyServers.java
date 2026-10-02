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
package io.repsy.os.server.protocols.maven.shared.keystore.support;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/** In-memory key servers for tests: a {@link RestClient} whose requests never reach a network. */
public final class StubKeyServers {

  private StubKeyServers() {}

  /** A client answering each request URI with the response the function returns. */
  public static @NonNull RestClient answering(
      final @NonNull Function<URI, ClientHttpResponse> answers) {

    return RestClient.builder()
        .requestInterceptor((request, body, execution) -> answers.apply(request.getURI()))
        .build();
  }

  public static @NonNull ClientHttpResponse notFound() {
    return new MockClientHttpResponse(new byte[0], HttpStatus.NOT_FOUND);
  }

  public static @NonNull ClientHttpResponse ok(final @NonNull String body) {
    final var response =
        new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
    response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);

    return response;
  }
}
