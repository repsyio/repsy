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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import java.util.ArrayList;
import java.util.List;
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
 * <p>RPS-1442 went through the routes RPS-1389 left: the Ruby indexes ({@code .gemspec.rz}, {@code
 * *specs.4.8.gz}, a compact index {@code /info/foo.rb} whose dotted gem name reads as an
 * extension), the 404 of a Go file URL, and the JSON of the Cargo and NuGet indexes, which are not
 * files and must carry no name. The Ruby {@code HEAD} answers the header of its {@code GET}. Go,
 * NuGet and Cargo have no {@code HEAD} handler at all: a {@code HEAD} is the router's "unknownPath"
 * 404, which is not pinned here.
 *
 * <p>The packages are pushed (NuGet: written to storage, its push needs a committed repo) in the
 * default rolled-back transaction and read back on the protocol port.
 */
@DisplayName("Downloads name the file they serve (RPS-1389, RPS-1442)")
class ProtocolDownloadContentDispositionIT extends AbstractIntegrationTest {

  private static final String RUBY_GEM = "dispo-gem";
  private static final String RUBY_DOTTED_GEM = "dispo.rb";
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
  @DisplayName("Ruby: the .gemspec.rz and the three specs indexes are attachments named after them")
  void rubyIndexFiles() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-cd"));
    this.publishGem(repo.getName(), RUBY_GEM, "1.2.3");

    for (final var path : rubyFilePaths()) {
      final var response = this.call(get("/{repo}" + path, repo.getName()), 200);

      assertThat(response.getHeader(CONTENT_DISPOSITION))
          .as(path)
          .isEqualTo("attachment; filename=\"" + path.substring(path.lastIndexOf('/') + 1) + "\"");
    }
  }

  @Test
  @DisplayName("Ruby: the compact index is text, a dotted gem name is not named f.txt")
  void rubyCompactIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-cd"));
    this.publishGem(repo.getName(), RUBY_GEM, "1.2.3");
    this.publishGem(repo.getName(), RUBY_DOTTED_GEM, "1.0.0");

    final var info = this.call(get("/{repo}/info/" + RUBY_GEM, repo.getName()), 200);
    final var dotted = this.call(get("/{repo}/info/" + RUBY_DOTTED_GEM, repo.getName()), 200);
    final var names = this.call(get("/{repo}/names", repo.getName()), 200);
    final var versions = this.call(get("/{repo}/versions", repo.getName()), 200);

    assertThat(info.getHeader(CONTENT_DISPOSITION)).isEqualTo("inline");
    assertThat(dotted.getHeader(CONTENT_DISPOSITION)).isEqualTo("inline");
    assertThat(names.getHeader(CONTENT_DISPOSITION)).isNull();
    assertThat(versions.getHeader(CONTENT_DISPOSITION)).isNull();
  }

  @Test
  @DisplayName("Ruby: a HEAD answers the Content-Disposition of the GET it mirrors")
  void rubyHeadMirrorsGet() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby-cd"));
    this.publishGem(repo.getName(), RUBY_GEM, "1.2.3");
    this.publishGem(repo.getName(), RUBY_DOTTED_GEM, "1.0.0");
    final var paths = new ArrayList<>(rubyFilePaths());
    paths.addAll(List.of("/info/" + RUBY_GEM, "/info/" + RUBY_DOTTED_GEM, "/names", "/versions"));

    for (final var path : paths) {
      final var get = this.call(get("/{repo}" + path, repo.getName()), 200);
      final var head = this.call(head("/{repo}" + path, repo.getName()), 200);

      assertThat(head.getHeader(CONTENT_DISPOSITION))
          .as("HEAD %s", path)
          .isEqualTo(get.getHeader(CONTENT_DISPOSITION));
    }
  }

  private static List<String> rubyFilePaths() {
    return List.of(
        "/gems/" + RUBY_GEM + "-1.2.3.gem",
        "/quick/Marshal.4.8/" + RUBY_GEM + "-1.2.3.gemspec.rz",
        "/specs.4.8.gz",
        "/latest_specs.4.8.gz",
        "/prerelease_specs.4.8.gz");
  }

  private void publishGem(final String repoName, final String name, final String version)
      throws Exception {
    this.call(
        post(PUBLISH_PATH, repoName)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(gem(name, version)),
        200);
  }

  @Test
  @DisplayName("Cargo: config.json and the sparse index are data and carry no file name")
  void cargoIndexHasNoDisposition() throws Exception {
    final var repo = this.seedRepo(RepoType.CARGO, uniqueRepoName("cargo-cd"));
    this.call(put("/{repo}/api/v1/crates/new", repo.getName()).content(cargoPublishBody()), 200);

    final var config = this.call(get("/{repo}/config.json", repo.getName()), 200);
    final var index = this.call(get("/{repo}/my/-c/my-crate", repo.getName()), 200);

    assertThat(config.getHeader(CONTENT_DISPOSITION)).isNull();
    assertThat(index.getHeader(CONTENT_DISPOSITION)).isNull();
  }

  @Test
  @DisplayName("NuGet: the service index is data and carries no file name")
  void nugetServiceIndexHasNoDisposition() throws Exception {
    final var repo = this.seedRepo(RepoType.NUGET, uniqueRepoName("nuget-cd"));

    final var response = this.call(get("/{repo}/v3/index.json", repo.getName()), 200);

    assertThat(response.getHeader(CONTENT_DISPOSITION)).isNull();
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

  @Test
  @DisplayName("Go: the 404 of a module file is not named f.txt either")
  void goNotFoundNamesNoFile() throws Exception {
    final var repo = this.seedRepo(RepoType.GOLANG, uniqueRepoName("go-cd"));

    for (final var extension : List.of("zip", "info", "mod")) {
      final var response =
          this.call(get("/{repo}/" + GO_MODULE + "/@v/v9.9.9." + extension, repo.getName()), 404);

      assertThat(response.getHeader(CONTENT_DISPOSITION)).as(extension).isEqualTo("inline");
    }
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
