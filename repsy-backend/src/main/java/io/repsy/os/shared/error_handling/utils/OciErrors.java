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

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.os.shared.error_handling.dtos.OciErrorCode;
import io.repsy.os.shared.error_handling.dtos.OciErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Maps the panel error envelope to the error body of the OCI distribution specification, for the
 * Docker and Helm {@code /v2/} endpoints.
 */
@UtilityClass
@NullMarked
public final class OciErrors {

  private static final String ERR_UNAUTHORIZED = "unauthorizedRequest";
  private static final String OCI_ROOT = "/v2";
  private static final String OCI_PREFIX = "/v2/";

  /** The failures the registry names itself, by the message id of the exception. */
  private static final Map<String, OciErrorCode> CODE_BY_MSG_ID =
      Map.ofEntries(
          Map.entry("chartNameMismatch", OciErrorCode.NAME_INVALID),
          Map.entry("manifestNameTooLong", OciErrorCode.NAME_INVALID),
          Map.entry("dockerImageNameInvalid", OciErrorCode.NAME_INVALID),
          Map.entry("dockerReferenceInvalid", OciErrorCode.TAG_INVALID),
          Map.entry("dockerDigestInvalid", OciErrorCode.DIGEST_INVALID),
          Map.entry("dockerDigestAlgorithmUnsupported", OciErrorCode.DIGEST_INVALID),
          Map.entry("dockerMediaTypeTooLong", OciErrorCode.MANIFEST_INVALID),
          Map.entry("dockerPlatformTooLong", OciErrorCode.MANIFEST_INVALID),
          Map.entry("manifestInvalidJson", OciErrorCode.MANIFEST_INVALID),
          Map.entry("manifestLayersMissing", OciErrorCode.MANIFEST_INVALID),
          Map.entry("manifestLayerInvalid", OciErrorCode.MANIFEST_INVALID),
          Map.entry("digestMismatch", OciErrorCode.DIGEST_INVALID),
          Map.entry("blobDigestUnsupported", OciErrorCode.DIGEST_INVALID),
          Map.entry("manifestNotFound", OciErrorCode.MANIFEST_UNKNOWN),
          Map.entry("tagNotFound", OciErrorCode.MANIFEST_UNKNOWN),
          Map.entry("imageNotFound", OciErrorCode.NAME_UNKNOWN),
          Map.entry("unknownPath", OciErrorCode.NAME_UNKNOWN));

  private static final Set<String> MISSING_BLOB_MSG_IDS = Set.of("blobNotFound", "layerNotFound");

  /** The codes of the statuses that are not a failure of a named resource. */
  private static final Map<Integer, OciErrorCode> CODE_BY_STATUS =
      Map.of(
          HttpStatus.UNAUTHORIZED.value(), OciErrorCode.UNAUTHORIZED,
          HttpStatus.FORBIDDEN.value(), OciErrorCode.DENIED,
          HttpStatus.CONFLICT.value(), OciErrorCode.DENIED,
          HttpStatus.PAYLOAD_TOO_LARGE.value(), OciErrorCode.SIZE_INVALID,
          HttpStatus.TOO_MANY_REQUESTS.value(), OciErrorCode.TOOMANYREQUESTS);

  private static final String MANIFESTS_SEGMENT = "/manifests/";
  private static final String BLOBS_SEGMENT = "/blobs/";
  private static final String BLOB_UPLOADS_SEGMENT = "/blobs/uploads/";

  /**
   * Tells whether the request is served by the OCI distribution API, whose clients expect the
   * distribution error body. The protocol router puts the request path in the servlet path.
   *
   * @param request The failed request
   * @return True for {@code /v2} and everything below it
   */
  public static boolean isOciRequest(final HttpServletRequest request) {

    final var path = request.getServletPath();

    return OCI_ROOT.equals(path) || path.startsWith(OCI_PREFIX);
  }

  /**
   * Builds the 401 answer that asks a client to authenticate. It carries the challenge header, and
   * on an OCI request the distribution error body ({@code UNAUTHORIZED}) that registry clients
   * expect next to it; other requests keep the bare status and header.
   *
   * @param request The unauthenticated request
   * @param challenge The {@code WWW-Authenticate} header value
   * @param resp Factory of the localised message
   * @return The response to return from a pre-processor
   */
  public static ResponseEntity<Object> challenge(
      final HttpServletRequest request, final String challenge, final RestResponseFactory resp) {

    final var response =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, challenge);

    if (!isOciRequest(request)) {
      return response.build();
    }

    return response.contentType(MediaType.APPLICATION_JSON).body(resp.error(ERR_UNAUTHORIZED));
  }

  /**
   * Converts a panel error envelope to the distribution error body. The message is the envelope
   * text, so it stays localised and free of internals, and the detail is the envelope data (the
   * message id when the exception carried nothing else).
   *
   * @param envelope The panel error envelope
   * @param status The HTTP status of the response
   * @param path The request path, which tells what a not-found or missing blob refers to
   * @return The distribution error body
   */
  public static OciErrorResponse toOciError(
      final RestResponse<?> envelope, final int status, final String path) {

    final var text = envelope.getText();
    final var data = envelope.getData();

    return OciErrorResponse.of(
        resolveCode(envelope.getMsgId(), status, path),
        text != null ? text : envelope.getMsgId(),
        data != null ? data : envelope.getMsgId());
  }

  /**
   * Picks the distribution error code for a failed request. The message id names the failure where
   * the registry knows what went wrong; every other failure gets the code of its HTTP status.
   *
   * @param msgId The panel message id
   * @param status The HTTP status of the response
   * @param path The request path
   * @return The error code
   */
  public static OciErrorCode resolveCode(final String msgId, final int status, final String path) {

    if (MISSING_BLOB_MSG_IDS.contains(msgId)) {
      return missingBlobCode(path);
    }

    final var byMsgId = CODE_BY_MSG_ID.get(msgId);

    if (byMsgId != null) {
      return byMsgId;
    }

    return codeOfStatus(status, path);
  }

  /** A manifest push that names a blob the registry lacks fails with its own code. */
  private static OciErrorCode missingBlobCode(final String path) {

    return path.contains(MANIFESTS_SEGMENT)
        ? OciErrorCode.MANIFEST_BLOB_UNKNOWN
        : OciErrorCode.BLOB_UNKNOWN;
  }

  private static OciErrorCode codeOfStatus(final int status, final String path) {

    if (status == HttpStatus.NOT_FOUND.value()) {
      return notFoundCode(path);
    }

    if (status == HttpStatus.BAD_REQUEST.value()) {
      return badRequestCode(path);
    }

    return CODE_BY_STATUS.getOrDefault(
        status,
        status >= HttpStatus.INTERNAL_SERVER_ERROR.value()
            ? OciErrorCode.UNKNOWN
            : OciErrorCode.UNSUPPORTED);
  }

  /** A rejected request on a manifest means the manifest, or the chart it carries, is invalid. */
  private static OciErrorCode badRequestCode(final String path) {

    return path.contains(MANIFESTS_SEGMENT)
        ? OciErrorCode.MANIFEST_INVALID
        : OciErrorCode.UNSUPPORTED;
  }

  private static OciErrorCode notFoundCode(final String path) {

    if (path.contains(BLOB_UPLOADS_SEGMENT)) {
      return OciErrorCode.BLOB_UPLOAD_UNKNOWN;
    }

    if (path.contains(BLOBS_SEGMENT)) {
      return OciErrorCode.BLOB_UNKNOWN;
    }

    if (path.contains(MANIFESTS_SEGMENT)) {
      return OciErrorCode.MANIFEST_UNKNOWN;
    }

    return OciErrorCode.NAME_UNKNOWN;
  }
}
