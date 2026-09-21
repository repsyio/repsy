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
package io.repsy.os.server.protocols.maven.shared.keystore.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The detached-signature check of a Maven upload, with real OpenPGP signatures and a key server
 * that is an in-memory exchange function (no network). RPS-1186: a refused signature answers the
 * fixed {@code artifactSignatureNotVerified} id, not the BouncyCastle text.
 */
@DisplayName("PGPVerifierService")
class PGPVerifierServiceTest {

  private static final byte[] POM = "<project>lib 1.0</project>".getBytes(UTF_8);
  private static final byte[] OTHER_POM = "<project>lib 2.0</project>".getBytes(UTF_8);
  private static final String NOT_VERIFIED = "artifactSignatureNotVerified";

  private static PgpTestKeys keys;

  private final List<URI> asked = new ArrayList<>();

  @BeforeAll
  static void generateKey() {
    keys = PgpTestKeys.generate();
  }

  private PGPVerifierService serviceAnswering(final Function<URI, ClientResponse> answers) {
    return new PGPVerifierService(
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  this.asked.add(request.url());

                  return Mono.just(answers.apply(request.url()));
                })
            .build());
  }

  private PGPVerifierService serviceWithTheKey() {
    return this.serviceAnswering(uri -> keyResponse());
  }

  private static ClientResponse keyResponse() {
    return ClientResponse.create(HttpStatus.OK)
        .header(HttpHeaders.CONTENT_TYPE, "text/plain")
        .body(keys.armoredPublicKey())
        .build();
  }

  private static ClientResponse notFound() {
    return ClientResponse.create(HttpStatus.NOT_FOUND).build();
  }

  private static Resource resource(final String text) {
    return new ByteArrayResource(text.getBytes(UTF_8));
  }

  @Test
  @DisplayName("accepts the detached signature of the stored file")
  void verifiesADetachedSignatureOfTheStoredFile() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatCode(
            () -> this.serviceWithTheKey().verify(new ByteArrayResource(POM), signature, null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a signature made over other bytes with the fixed message id")
  void refusesASignatureOfOtherBytes() {
    final var signature = resource(keys.detachedSignature(OTHER_POM));

    assertThatThrownBy(
            () -> this.serviceWithTheKey().verify(new ByteArrayResource(POM), signature, null))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);
  }

  @Test
  @DisplayName("refuses an empty .asc, and never asks a key server about it")
  void refusesAnEmptyAsc() {
    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), new ByteArrayResource(new byte[0]), null))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("refuses an armored block that holds no signature packet, with the fixed id")
  void refusesAnAscWithoutASignaturePacket() {
    final var publicKeyInsteadOfSignature = resource(keys.armoredPublicKey());

    assertThatThrownBy(
            () ->
                this.serviceWithTheKey()
                    .verify(new ByteArrayResource(POM), publicKeyInsteadOfSignature, null))
        .isInstanceOf(SignatureNotVerifiedException.class)
        .hasMessage(NOT_VERIFIED);

    assertThat(this.asked).isEmpty();
  }

  @Test
  @DisplayName("answers itemNotFound when no key server knows the key")
  void answersItemNotFoundWhenNoServerHasTheKey() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(uri -> notFound())
                    .verify(new ByteArrayResource(POM), signature, List.of("keys.acme.com")))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("no public key found with Id %016X".formatted(keys.keyId()));

    // The repo's own server, then both public ones.
    assertThat(this.asked).hasSize(3);
  }

  @Test
  @DisplayName(
      "asks the custom hosts of the repo first, and stops at the first one that has the key")
  void asksTheRepoCustomHostsFirst() {
    final var signature = resource(keys.detachedSignature(POM));
    final var keyId = "%016X".formatted(keys.keyId());
    final var acme = "https://keys.acme.com/pks/lookup?op=get&search=0x" + keyId;
    final var mirror = "https://mirror.acme.com/pks/lookup?op=get&search=0x" + keyId;

    final var service =
        this.serviceAnswering(uri -> uri.toString().equals(mirror) ? keyResponse() : notFound());

    assertThatCode(
            () ->
                service.verify(
                    new ByteArrayResource(POM),
                    signature,
                    // A scheme or a path in the stored host is dropped, only the host is used.
                    List.of("keys.acme.com", "https://mirror.acme.com/somewhere")))
        .doesNotThrowAnyException();

    assertThat(this.asked).extracting(URI::toString).containsExactly(acme, mirror);
  }

  @Test
  @DisplayName("falls back to the public key servers when the custom hosts do not have the key")
  void fallsBackToThePublicServers() {
    final var signature = resource(keys.detachedSignature(POM));

    final var service =
        this.serviceAnswering(
            uri -> uri.getHost().equals("keys.acme.com") ? notFound() : keyResponse());

    assertThatCode(
            () -> service.verify(new ByteArrayResource(POM), signature, List.of("keys.acme.com")))
        .doesNotThrowAnyException();

    assertThat(this.asked.getFirst().getHost()).isEqualTo("keys.acme.com");
    assertThat(this.asked).hasSize(2);
    assertThat(this.asked.get(1).getHost()).isIn("keyserver.ubuntu.com", "keys.openpgp.org");
  }

  @Test
  @DisplayName("ignores a key server answer that is not a key block")
  void ignoresAnAnswerThatIsNotAKeyBlock() {
    final var signature = resource(keys.detachedSignature(POM));

    assertThatThrownBy(
            () ->
                this.serviceAnswering(
                        uri ->
                            ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                                .body("<html>not a key</html>")
                                .build())
                    .verify(new ByteArrayResource(POM), signature, null))
        .isInstanceOf(ItemNotFoundException.class);
  }
}
