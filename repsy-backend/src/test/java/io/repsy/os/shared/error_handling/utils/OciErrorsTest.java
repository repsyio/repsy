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
package io.repsy.os.shared.error_handling.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.core.response.dtos.ResponseType;
import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.shared.error_handling.dtos.OciErrorCode;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

/** RPS-1039: how a panel error is turned into an OCI distribution error. */
@DisplayName("OciErrors")
class OciErrorsTest {

  private static final String MANIFEST_PATH = "/v2/repo/app/manifests/latest";
  private static final String BLOB_PATH = "/v2/repo/app/blobs/sha256:abc";
  private static final String UPLOAD_PATH = "/v2/repo/app/blobs/uploads/id";

  private static MockHttpServletRequest request(final String servletPath) {
    final var request = new MockHttpServletRequest();
    request.setServletPath(servletPath);
    return request;
  }

  private static RestResponseFactory factory() {
    final var messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    return new RestResponseFactory(messageSource);
  }

  @ParameterizedTest(name = "{0} is an OCI request")
  @ValueSource(strings = {"/v2", "/v2/", "/v2/token", "/v2/repo/app/manifests/latest"})
  @DisplayName("recognises the registry root and everything below it")
  void ociPaths(final String path) {
    assertThat(OciErrors.isOciRequest(request(path))).isTrue();
  }

  @ParameterizedTest(name = "{0} is not an OCI request")
  @ValueSource(
      strings = {"", "/", "/v20", "/v2x/repo", "/api/repos", "/repo/index.yaml", "/repo/v2/x"})
  @DisplayName("leaves every other path to the panel envelope")
  void otherPaths(final String path) {
    assertThat(OciErrors.isOciRequest(request(path))).isFalse();
  }

  static Stream<Arguments> codes() {
    return Stream.of(
        Arguments.of("chartNameMismatch", 400, MANIFEST_PATH, OciErrorCode.NAME_INVALID),
        Arguments.of("manifestInvalidJson", 400, MANIFEST_PATH, OciErrorCode.MANIFEST_INVALID),
        Arguments.of("manifestLayersMissing", 400, MANIFEST_PATH, OciErrorCode.MANIFEST_INVALID),
        Arguments.of("manifestLayerInvalid", 400, MANIFEST_PATH, OciErrorCode.MANIFEST_INVALID),
        Arguments.of("chartYamlInvalid", 400, MANIFEST_PATH, OciErrorCode.MANIFEST_INVALID),
        Arguments.of("digestMismatch", 400, MANIFEST_PATH, OciErrorCode.DIGEST_INVALID),
        Arguments.of("manifestNotFound", 404, MANIFEST_PATH, OciErrorCode.MANIFEST_UNKNOWN),
        Arguments.of("tagNotFound", 404, MANIFEST_PATH, OciErrorCode.MANIFEST_UNKNOWN),
        Arguments.of("imageNotFound", 404, MANIFEST_PATH, OciErrorCode.NAME_UNKNOWN),
        Arguments.of("unknownPath", 404, MANIFEST_PATH, OciErrorCode.NAME_UNKNOWN),
        Arguments.of("blobNotFound", 404, BLOB_PATH, OciErrorCode.BLOB_UNKNOWN),
        Arguments.of("layerNotFound", 404, BLOB_PATH, OciErrorCode.BLOB_UNKNOWN),
        Arguments.of("blobNotFound", 404, MANIFEST_PATH, OciErrorCode.MANIFEST_BLOB_UNKNOWN),
        Arguments.of("layerNotFound", 404, MANIFEST_PATH, OciErrorCode.MANIFEST_BLOB_UNKNOWN),
        Arguments.of("itemNotFound", 404, UPLOAD_PATH, OciErrorCode.BLOB_UPLOAD_UNKNOWN),
        Arguments.of("resourceNotFound", 404, BLOB_PATH, OciErrorCode.BLOB_UNKNOWN),
        Arguments.of("resourceNotFound", 404, MANIFEST_PATH, OciErrorCode.MANIFEST_UNKNOWN),
        Arguments.of("itemNotFound", 404, "/v2/repo/app/tags/list", OciErrorCode.NAME_UNKNOWN),
        Arguments.of("unAuthorized", 401, MANIFEST_PATH, OciErrorCode.UNAUTHORIZED),
        Arguments.of("accessNotAllowed", 403, MANIFEST_PATH, OciErrorCode.DENIED),
        Arguments.of("chartAlreadyExists", 409, MANIFEST_PATH, OciErrorCode.DENIED),
        Arguments.of("payloadTooLarge", 413, BLOB_PATH, OciErrorCode.SIZE_INVALID),
        Arguments.of("tooManyRequests", 429, BLOB_PATH, OciErrorCode.TOOMANYREQUESTS),
        Arguments.of("validationError", 400, UPLOAD_PATH, OciErrorCode.UNSUPPORTED),
        Arguments.of("methodNotSupported", 405, BLOB_PATH, OciErrorCode.UNSUPPORTED),
        Arguments.of("unsupportedMediaType", 415, MANIFEST_PATH, OciErrorCode.UNSUPPORTED),
        Arguments.of("errorOccurred", 500, MANIFEST_PATH, OciErrorCode.UNKNOWN),
        Arguments.of("scanExecutorSaturated", 503, MANIFEST_PATH, OciErrorCode.UNKNOWN));
  }

  @ParameterizedTest(name = "{0} ({1}) on {2} is {3}")
  @MethodSource("codes")
  @DisplayName("picks the code of the failure, or of its status")
  void resolvesCode(
      final String msgId, final int status, final String path, final OciErrorCode expected) {
    assertThat(OciErrors.resolveCode(msgId, status, path)).isEqualTo(expected);
  }

  @Test
  @DisplayName("keeps the localised text as the message and the data as the detail")
  void convertsEnvelope() {
    final RestResponse<String> envelope = factory().error("manifestNotFound", "manifestNotFound");

    final var body = OciErrors.toOciError(envelope, 404, MANIFEST_PATH);

    assertThat(body.errors()).hasSize(1);
    assertThat(body.errors().getFirst().code()).isEqualTo("MANIFEST_UNKNOWN");
    assertThat(body.errors().getFirst().message()).isEqualTo("Manifest not found.");
    assertThat(body.errors().getFirst().detail()).isEqualTo("manifestNotFound");
  }

  @Test
  @DisplayName("falls back to the message id when the envelope has no data or text")
  void convertsBareEnvelope() {
    final var envelope = new RestResponse<String>("errorOccurred", ResponseType.ERROR);

    final var body = OciErrors.toOciError(envelope, 500, MANIFEST_PATH);

    assertThat(body.errors().getFirst().code()).isEqualTo("UNKNOWN");
    assertThat(body.errors().getFirst().message()).isEqualTo("errorOccurred");
    assertThat(body.errors().getFirst().detail()).isEqualTo("errorOccurred");
  }

  @Test
  @DisplayName("answers a challenge on an OCI request with the header and the UNAUTHORIZED body")
  void ociChallenge() {
    final var response =
        OciErrors.challenge(request(MANIFEST_PATH), "Basic realm=\"Repsy\"", factory());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
        .isEqualTo("Basic realm=\"Repsy\"");
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isInstanceOf(RestResponse.class);
    assertThat(((RestResponse<?>) response.getBody()).getMsgId()).isEqualTo("unauthorizedRequest");
  }

  @Test
  @DisplayName("answers a challenge on any other request with the header alone")
  void classicChallenge() {
    final var response =
        OciErrors.challenge(request("/repo/index.yaml"), "Basic realm=\"Repsy\"", factory());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
        .isEqualTo("Basic realm=\"Repsy\"");
    assertThat(response.getBody()).isNull();
  }
}
