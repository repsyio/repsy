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
package io.repsy.os.server.protocols.shared;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.nuget.shared.storage.NuGetStorageService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1389: a download that sets no {@code Content-Disposition} of its own is given {@code
 * inline;filename=f.txt} by Spring's resource converter whenever the last segment of the URL has an
 * extension, so a browser or a download tool saved a {@code .gem}, {@code .nupkg} or a Go module
 * zip as {@code f.txt}. Cargo's download URL ends in {@code /download}, so it got no header at all
 * and was saved as {@code download}. Each of them now names the file it is.
 *
 * <p>The packages are pushed (NuGet: written to storage, its push needs a committed repo) in the
 * default rolled-back transaction and read back on the protocol port.
 */
@DisplayName("Downloads name the file they serve (RPS-1389)")
class ProtocolDownloadContentDispositionIT extends AbstractIntegrationTest {

  private static final String CRATE = "my-crate";
  private static final String CRATE_VERSION = "1.0.0";
  private static final String GO_MODULE = "example.com/disposition";
  private static final String GO_VERSION = "v1.2.3";
  private static final String NUGET_ID = "repsy.disposition";
  private static final String NUGET_VERSION = "1.0.0";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private NuGetStorageService nugetStorageService;

  private MockHttpServletResponse call(
      final AbstractMockHttpServletRequestBuilder<?> request, final int expectedStatus)
      throws Exception {
    final var response =
        this.mockMvc
            .perform(
                request.header(AUTHORIZATION, this.adminProtocolBearerToken()).with(protocolPort()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus())
        .as("status, body: %s", response.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(expectedStatus);

    return response;
  }

  @Test
  @DisplayName("Ruby: the .gem is an attachment named after the gem file")
  void rubyGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-cd"));
    this.call(
        post(PUBLISH_PATH, repo.getName())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(gem("dispo-gem", "1.2.3")),
        200);

    final var response = this.call(get("/{repo}/gems/dispo-gem-1.2.3.gem", repo.getName()), 200);

    assertThat(response.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"dispo-gem-1.2.3.gem\"");
  }

  @Test
  @DisplayName("Cargo: the crate is an attachment named <crate>-<version>.crate")
  void cargoCrate() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo-cd"));
    this.call(put("/{repo}/api/v1/crates/new", repo.getName()).content(cargoPublishBody()), 200);

    final var response =
        this.call(
            get(
                "/{repo}/api/v1/crates/{crate}/{version}/download",
                repo.getName(),
                CRATE,
                CRATE_VERSION),
            200);

    assertThat(response.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"my-crate-1.0.0.crate\"");
  }

  @Test
  @DisplayName("NuGet: the .nupkg is an attachment and the .nuspec is shown under its own name")
  void nugetPackage() throws Exception {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-cd"));
    this.nugetStorageService.writePackage(
        repo.getId(),
        NUGET_ID,
        NUGET_VERSION,
        new ByteArrayInputStream(new byte[] {1, 2, 3}),
        "<package/>".getBytes(StandardCharsets.UTF_8));

    final var nupkg =
        this.call(
            get(
                "/{repo}/v3/package/{id}/{version}/{id}.{version}.nupkg",
                repo.getName(),
                NUGET_ID,
                NUGET_VERSION,
                NUGET_ID,
                NUGET_VERSION),
            200);
    final var nuspec =
        this.call(
            get(
                "/{repo}/v3/package/{id}/{version}/{id}.nuspec",
                repo.getName(),
                NUGET_ID,
                NUGET_VERSION,
                NUGET_ID),
            200);

    assertThat(nupkg.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"repsy.disposition.1.0.0.nupkg\"");
    assertThat(nuspec.getHeader(CONTENT_DISPOSITION))
        .isEqualTo("inline; filename=\"repsy.disposition.nuspec\"");
  }

  @Test
  @DisplayName("Go: the module zip is an attachment, .info and .mod are shown under their names")
  void goModule() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("go-cd"));
    this.call(
        put("/{repo}/" + GO_MODULE + "/@v/" + GO_VERSION, repo.getName())
            .contentType("application/zip")
            .content(goModuleZip()),
        200);
    final var base = "/{repo}/" + GO_MODULE + "/@v/" + GO_VERSION;

    final var zip = this.call(get(base + ".zip", repo.getName()), 200);
    final var info = this.call(get(base + ".info", repo.getName()), 200);
    final var mod = this.call(get(base + ".mod", repo.getName()), 200);

    assertThat(zip.getHeader(CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"v1.2.3.zip\"");
    assertThat(info.getHeader(CONTENT_DISPOSITION)).isEqualTo("inline; filename=\"v1.2.3.info\"");
    assertThat(mod.getHeader(CONTENT_DISPOSITION)).isEqualTo("inline; filename=\"v1.2.3.mod\"");
  }

  @Test
  @DisplayName("Go: the version list and @latest have no extension and get no header")
  void goListAndLatestHaveNoDisposition() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("go-cd"));
    this.call(
        put("/{repo}/" + GO_MODULE + "/@v/" + GO_VERSION, repo.getName())
            .contentType("application/zip")
            .content(goModuleZip()),
        200);

    final var list = this.call(get("/{repo}/" + GO_MODULE + "/@v/list", repo.getName()), 200);
    final var latest = this.call(get("/{repo}/" + GO_MODULE + "/@latest", repo.getName()), 200);

    assertThat(list.getHeader(CONTENT_DISPOSITION)).isNull();
    assertThat(latest.getHeader(CONTENT_DISPOSITION)).isNull();
  }

  private static byte[] goModuleZip() {
    final var out = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(out)) {
      final var prefix = GO_MODULE + "@" + GO_VERSION + "/";

      zip.putNextEntry(new ZipEntry(prefix + "go.mod"));
      zip.write(("module " + GO_MODULE + "\n\ngo 1.21\n").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(prefix + "disposition.go"));
      zip.write("package disposition\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return out.toByteArray();
  }

  /** The body of {@code cargo publish}: the metadata JSON and the {@code .crate}, each prefixed. */
  private static byte[] cargoPublishBody() {
    final var metadata =
        ("{\"name\":\"%s\",\"vers\":\"%s\",\"deps\":[],\"features\":{},\"authors\":[],"
                + "\"description\":\"RPS-1389 fixture\",\"license\":\"MIT\"}")
            .formatted(CRATE, CRATE_VERSION)
            .getBytes(StandardCharsets.UTF_8);
    final var crate = crateArchive();
    final var out = new ByteArrayOutputStream();

    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(metadata.length).array());
    out.writeBytes(metadata);
    out.writeBytes(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crate.length).array());
    out.writeBytes(crate);

    return out.toByteArray();
  }

  /** A minimal {@code .crate}: a gzipped tarball holding a manifest and a library root. */
  private static byte[] crateArchive() {
    final var bytes = new ByteArrayOutputStream();
    final var dir = CRATE + "-" + CRATE_VERSION + "/";

    try (final var tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
      addEntry(tar, dir + "Cargo.toml", "[package]\nname = \"" + CRATE + "\"\n");
      addEntry(tar, dir + "src/lib.rs", "");
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    return bytes.toByteArray();
  }

  private static void addEntry(
      final TarArchiveOutputStream tar, final String name, final String content)
      throws IOException {
    final var data = content.getBytes(StandardCharsets.UTF_8);
    final var entry = new TarArchiveEntry(name);

    entry.setSize(data.length);
    tar.putArchiveEntry(entry);
    tar.write(data);
    tar.closeArchiveEntry();
  }
}
