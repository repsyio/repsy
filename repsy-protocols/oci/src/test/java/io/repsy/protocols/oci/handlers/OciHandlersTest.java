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
package io.repsy.protocols.oci.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.oci.dtos.OciBlobInfo;
import io.repsy.protocols.oci.dtos.OciManifestInfo;
import io.repsy.protocols.oci.utils.OciPathUtils;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The shared OCI handlers over an in-memory store; the Docker and Helm handler tests and {@code
 * OciWireCharacterizationIT} cover them with each format's hooks.
 */
@DisplayName("Shared OCI handlers")
class OciHandlersTest {

  private static final String SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e";
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final HandlerRoute WRITE = HandlerRoute.of(Permission.WRITE, HttpMethod.PATCH);

  private final ProtocolProvider provider = mock(ProtocolProvider.class);
  private final Map<String, Long> uploads = new HashMap<>();

  private static ProtocolContext context(final String relativePath) {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName("repo")
            .relativePath(new RelativePath(relativePath))
            .repoInfo(new BaseRepoInfo<>())
            .build());
    return context;
  }

  private static MockHttpServletRequest request(final String method, final String body) {
    final var request = new MockHttpServletRequest(method, "/v2/repo/app/blobs/uploads/" + SESSION);
    request.setContent(body.getBytes());
    return request;
  }

  private final class Chunk extends AbstractOciUploadChunkProtocolMethodHandler<Object> {

    Chunk() {
      super(WRITE, _ -> Optional.empty(), new Object(), OciHandlersTest.this.provider);
    }

    @Override
    protected String reportedUploadId(final String uploadId) {
      return uploadId.toUpperCase();
    }

    @Override
    protected long uploadSize(
        final ProtocolContext context, final String name, final String uploadId) {
      final var size = OciHandlersTest.this.uploads.get(uploadId);

      if (size == null) {
        throw new ItemNotFoundException("resourceNotFound");
      }

      return size;
    }

    @Override
    protected long appendChunk(
        final ProtocolContext context,
        final HttpServletRequest request,
        final String name,
        final String uploadId) {
      return OciHandlersTest.this.uploads.merge(
          uploadId, (long) request.getContentLength(), Long::sum);
    }

    @Override
    protected String uploadLocation(
        final ProtocolContext context,
        final HttpServletRequest request,
        final String name,
        final String uploadId) {
      return "/" + name + "/" + uploadId;
    }
  }

  private final class Status extends AbstractOciUploadStatusProtocolMethodHandler<Object> {

    Status() {
      super(WRITE, _ -> Optional.empty(), new Object(), OciHandlersTest.this.provider);
    }

    @Override
    protected long uploadSize(
        final ProtocolContext context, final String name, final String uploadId) {
      return new Chunk().uploadSize(context, name, uploadId);
    }

    @Override
    protected String uploadLocation(
        final ProtocolContext context,
        final HttpServletRequest request,
        final String name,
        final String uploadId) {
      return "/" + name + "/" + uploadId;
    }
  }

  private final class Finalize extends AbstractOciUploadFinalizeProtocolMethodHandler<Object> {

    Finalize() {
      super(WRITE, _ -> Optional.empty(), new Object(), OciHandlersTest.this.provider);
    }

    @Override
    protected String finalizeUpload(
        final ProtocolContext context,
        final HttpServletRequest request,
        final String name,
        final String uploadId,
        final String digest) {
      return digest.toLowerCase();
    }

    @Override
    protected String blobLocation(
        final ProtocolContext context,
        final HttpServletRequest request,
        final String name,
        final String digest) {
      return "/" + name + "/blobs/" + digest;
    }
  }

  private final class Start extends AbstractOciUploadStartProtocolMethodHandler {

    Start() {
      super(WRITE, _ -> Optional.empty(), OciHandlersTest.this.provider);
    }

    @Override
    protected UUID startUpload(final ProtocolContext context, final HttpServletRequest request) {
      return UUID.fromString(SESSION);
    }

    @Override
    protected String uploadLocation(final HttpServletRequest request, final UUID uploadId) {
      return "/uploads/" + uploadId;
    }
  }

  @Test
  @DisplayName("a chunk is appended and answered with the session's Location, Range and id")
  void chunkIsAppended() throws Exception {
    final var response =
        new Chunk()
            .handle(
                context("/app/blobs/uploads/" + SESSION),
                request("PATCH", "hello"),
                new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/app/" + SESSION);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-4");
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID"))
        .isEqualTo(SESSION.toUpperCase());
  }

  @Test
  @DisplayName("a Content-Range that does not start at the session size is a 416, nothing written")
  void mismatchedContentRangeIsRefused() throws Exception {
    this.uploads.put(SESSION, 5L);
    final var request = request("PATCH", "xx");
    request.addHeader("Content-Range", "0-1");

    final var response =
        new Chunk()
            .handle(
                context("/app/blobs/uploads/" + SESSION), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-4");
    assertThat(this.uploads).containsEntry(SESSION, 5L);
  }

  @Test
  @DisplayName("a session with nothing written is size zero for the Content-Range check")
  void freshSessionIsSizeZero() throws Exception {
    final var request = request("PATCH", "abc");
    request.addHeader("Content-Range", "0-2");

    final var response =
        new Chunk()
            .handle(
                context("/app/blobs/uploads/" + SESSION), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-2");
  }

  @Test
  @DisplayName("a Content-Range the parser cannot read is ignored and the chunk appended")
  void unreadableContentRangeAppends() throws Exception {
    this.uploads.put(SESSION, 5L);
    final var request = request("PATCH", "!");
    request.addHeader("Content-Range", "bytes 0-0/1");

    final var response =
        new Chunk()
            .handle(
                context("/app/blobs/uploads/" + SESSION), request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-5");
  }

  @Test
  @DisplayName("the status of a session is a 204 with its Range; an unknown one throws")
  void statusAnswersTheRange() throws Exception {
    this.uploads.put(SESSION, 12L);
    final var context = context("/app/blobs/uploads/" + SESSION);

    final var response =
        new Status().handle(context, request("GET", ""), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(response.getHeaders().getFirst("Range")).isEqualTo("0-11");
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION);

    this.uploads.clear();
    assertThatThrownBy(
            () -> new Status().handle(context, request("GET", ""), new MockHttpServletResponse()))
        .isInstanceOf(ItemNotFoundException.class);
  }

  @Test
  @DisplayName("a finalize without digest is a 400; with one it is a 201 naming the stored blob")
  void finalizeNeedsADigest() throws Exception {
    final var context = context("/app/blobs/uploads/" + SESSION);

    assertThatThrownBy(
            () -> new Finalize().handle(context, request("PUT", ""), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);

    final var request = request("PUT", "");
    request.setParameter("digest", DIGEST.toUpperCase());
    final var response = new Finalize().handle(context, request, new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst("Docker-Content-Digest")).isEqualTo(DIGEST);
    assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/app/blobs/" + DIGEST);
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION);
  }

  @Test
  @DisplayName("an upload start names the one session in Location and Docker-Upload-UUID")
  void startNamesOneSession() {
    final var response =
        new Start()
            .handle(
                context("/app/blobs/uploads/"), request("POST", ""), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/uploads/" + SESSION);
    assertThat(response.getHeaders().getFirst("Docker-Upload-UUID")).isEqualTo(SESSION);
  }

  @Test
  @DisplayName("a blob check answers the stored blob's headers, or a bare 404")
  void blobCheck() {
    final var stored = Map.of(DIGEST, new OciBlobInfo(7, "application/octet-stream"));
    final var handler =
        new AbstractOciBlobCheckProtocolMethodHandler<Object>(
            HandlerRoute.of(Permission.READ, HttpMethod.HEAD),
            OciPathUtils.blob("sha256:[0-9a-f]{64}"),
            _ -> Optional.empty(),
            new Object(),
            this.provider) {
          @Override
          protected Optional<OciBlobInfo> findBlob(
              final ProtocolContext context, final String digest) {
            return Optional.ofNullable(stored.get(digest));
          }
        };

    final var found =
        handler.handle(
            context("/app/blobs/" + DIGEST), request("HEAD", ""), new MockHttpServletResponse());
    final var missing =
        handler.handle(
            context("/app/blobs/sha256:" + "b".repeat(64)),
            request("HEAD", ""),
            new MockHttpServletResponse());

    assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(found.getHeaders().getFirst("Content-Length")).isEqualTo("7");
    assertThat(found.getHeaders().getFirst("Docker-Content-Digest")).isEqualTo(DIGEST);
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missing.hasBody()).isFalse();
  }

  @Test
  @DisplayName("a blob pull serves the bytes with the format's content type")
  void blobPull() throws Exception {
    final var handler =
        new AbstractOciBlobPullProtocolMethodHandler<Object>(
            HandlerRoute.of(Permission.READ, HttpMethod.GET),
            OciPathUtils.blob("sha256:[0-9a-f]{64}"),
            "application/x-test",
            _ -> Optional.empty(),
            new Object(),
            this.provider) {
          @Override
          protected Resource getBlob(
              final ProtocolContext context,
              final HttpServletRequest request,
              final String digest) {
            return new ByteArrayResource("blob".getBytes());
          }
        };

    final var response =
        handler.handle(
            context("/app/blobs/" + DIGEST), request("GET", ""), new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/x-test");
    assertThat(response.getHeaders().getFirst("Docker-Content-Digest")).isEqualTo(DIGEST);
    assertThat(response.getBody()).isInstanceOf(ByteArrayResource.class);
  }

  @Test
  @DisplayName("a manifest check and pull answer the same headers; the check of a missing one 404s")
  void manifestCheckAndPull() throws Exception {
    final var manifest = new OciManifestInfo("application/x-manifest", "{\"é\":1}", DIGEST);
    final var check =
        new AbstractOciManifestCheckProtocolMethodHandler<Object>(
            HandlerRoute.of(Permission.READ, HttpMethod.HEAD),
            _ -> Optional.empty(),
            new Object(),
            this.provider) {
          @Override
          protected Optional<OciManifestInfo> findManifest(
              final ProtocolContext context,
              final HttpServletRequest request,
              final String name,
              final String reference) {
            return "1.0.0".equals(reference) ? Optional.of(manifest) : Optional.empty();
          }
        };
    final var pull =
        new AbstractOciManifestPullProtocolMethodHandler<Object>(
            HandlerRoute.of(Permission.READ, HttpMethod.GET),
            _ -> Optional.empty(),
            new Object(),
            this.provider) {
          @Override
          protected void checkAcceptable(final HttpServletRequest request) throws IOException {
            if ("text/plain".equals(request.getHeader("Accept"))) {
              throw new IOException("not acceptable");
            }
          }

          @Override
          protected OciManifestInfo getManifest(
              final ProtocolContext context,
              final HttpServletRequest request,
              final String name,
              final String reference) {
            return manifest;
          }
        };

    final var head =
        check.handle(
            context("/app/manifests/1.0.0"), request("HEAD", ""), new MockHttpServletResponse());
    final var get =
        pull.handle(
            context("/app/manifests/1.0.0"), request("GET", ""), new MockHttpServletResponse());
    final var missing =
        check.handle(
            context("/app/manifests/2.0.0"), request("HEAD", ""), new MockHttpServletResponse());
    final var notAcceptable = request("GET", "");
    notAcceptable.addHeader("Accept", "text/plain");

    assertThat(head.getHeaders().getFirst("Content-Length")).isEqualTo("8");
    assertThat(get.getHeaders()).isEqualTo(head.getHeaders());
    assertThat(get.getBody()).isEqualTo(manifest.content());
    assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThatThrownBy(
            () ->
                pull.handle(
                    context("/app/manifests/1.0.0"), notAcceptable, new MockHttpServletResponse()))
        .isInstanceOf(IOException.class);
  }
}
