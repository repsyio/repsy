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
package io.repsy.os.spa.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPS-1467: the single-page-app fallback answers {@code index.html} for a client route and never
 * for a path that looks like a bundled file (a missing script, style, font or image), and the real
 * files of the build are served as themselves. {@code MockMvc} does not run a {@code forward:}, so
 * a fallback is asserted as {@code forwardedUrl("/index.html")}.
 *
 * <p>Follows RPS-1445, where the remixicon fonts sat under {@code /media/}, came back as {@code
 * index.html} with a 200 and {@code text/html}, and every icon rendered as a box.
 */
@DisplayName("SPA fallback and static files (RPS-1467)")
class SpaControllerIT extends AbstractStaticFrontendIntegrationTest {

  /** What a browser sends for a font, script, style or image subresource, or curl by default. */
  private static final List<String> SUBRESOURCE_ACCEPTS =
      List.of(
          "*/*",
          "font/woff2;q=1.0,application/font-woff2;q=0.9,*/*;q=0.8",
          "text/css,*/*;q=0.1",
          "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");

  /** What a browser sends when it navigates (opens or reloads) a URL. */
  private static final String NAVIGATION_ACCEPT =
      "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

  private ResultActions request(final String uri, final String accept) throws Exception {

    final MockHttpServletRequestBuilder builder = get(URI.create(uri));
    if (accept != null) {
      builder.header(HttpHeaders.ACCEPT, accept);
    }

    return this.perform(builder);
  }

  private void assertIsFallback(final String uri, final String accept) throws Exception {

    this.request(uri, accept).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
  }

  private void assertIsNotFoundJson(final String uri, final String accept) throws Exception {

    final var result =
        this.request(uri, accept)
            .andExpect(status().isNotFound())
            .andExpect(forwardedUrl(null))
            .andExpect(content().string(not(containsString("<html"))))
            .andReturn();

    assertThat(result.getResponse().getContentType())
        .as("%s must not come back as a page", uri)
        .doesNotContain("html");
  }

  @Test
  @DisplayName("the root and a client route fall back to index.html")
  void clientRoutesFallBack() throws Exception {

    for (final var uri :
        List.of("/", "/login", "/repositories", "/repositories/some-repo/overview")) {
      this.assertIsFallback(uri, NAVIGATION_ACCEPT);
      // No Accept header at all, as a plain HTTP client sends it, is a client route too.
      this.assertIsFallback(uri, null);
    }
  }

  @Test
  @DisplayName("a route whose last segment is a version or a dotted name falls back")
  void dottedRoutesFallBack() throws Exception {

    for (final var uri :
        List.of(
            "/repositories/repo/cargo/serde/1.0.0",
            "/repositories/repo/maven/com.example/lib/2.0.0.RELEASE",
            "/repositories/repo/npm/@scope/pkg/1.0.0-beta.1",
            "/repositories/repo/pypi/zope.interface/5.4.0",
            "/repositories/repo/npm/lodash.merge")) {
      this.assertIsFallback(uri, NAVIGATION_ACCEPT);
      this.assertIsFallback(uri, null);
      this.assertIsFallback(uri, "*/*");
    }
  }

  @Test
  @DisplayName("a package named like a script (chart.js) is still a route when a page is asked for")
  void assetLikePackageNameFallsBackOnNavigation() throws Exception {

    this.assertIsFallback("/repositories/repo/npm/chart.js", NAVIGATION_ACCEPT);
    this.assertIsFallback("/repositories/repo/npm/@scope/chart.js/1.0.0", NAVIGATION_ACCEPT);
    // ... and a subresource request for the same URL is not a page.
    this.assertIsNotFoundJson("/repositories/repo/npm/chart.js", "*/*");
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("a missing bundled file is a 404 that is not HTML, never index.html with a 200")
  @ValueSource(
      strings = {
        "/media/remixicon-A1B2C3.woff2",
        "/media/remixicon-A1B2C3.woff",
        "/media/remixicon-A1B2C3.ttf",
        "/media/remixicon-A1B2C3.eot",
        "/media/remixicon-A1B2C3.svg",
        "/static/js/app.js",
        "/chunks/chunk-Z9Y8X7.js",
        "/deep/nested/dir/module.mjs",
        "/deep/nested/dir/styles.css",
        "/deep/main.js.map",
        "/deep/logo.png",
        "/deep/photo.JPG",
        "/deep/font.WOFF2",
        "/deep/manifest.webmanifest",
        "/deep/robots.txt",
        "/deep/data.json",
        "/deep/icon.ico",
        "/deep/module.wasm"
      })
  void missingBundledFileIsNotFound(final String uri) throws Exception {

    for (final var accept : SUBRESOURCE_ACCEPTS) {
      this.assertIsNotFoundJson(uri, accept);
    }
    this.assertIsNotFoundJson(uri, null);
  }

  @Test
  @DisplayName("the 404 for a missing bundled file is the API's JSON error, like /assets/")
  void notFoundBodyIsTheJsonErrorOfTheApi() throws Exception {

    this.request("/media/remixicon-A1B2C3.woff2", "*/*")
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/json")))
        .andExpect(jsonPath("$.msgId").value("itemNotFound"));

    // /assets/ is not handled by the SPA controller; the two must agree.
    this.request("/assets/missing.woff2", "*/*")
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/json")));
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("a filename hidden behind the URL syntax is still a missing bundled file")
  @ValueSource(
      strings = {
        "/media/font.woff2/",
        "/media/font%2Ewoff2",
        "/media/font.woff2;jsessionid=1",
        "/media/font.woff2?v=1",
        "/media//font.woff2"
      })
  void urlSyntaxDoesNotHideAFile(final String uri) throws Exception {

    this.assertIsNotFoundJson(uri, "*/*");
  }

  @Test
  @DisplayName("a page navigation to a missing bundled file gets the panel, not a 404")
  void navigationToMissingFileGetsThePanel() throws Exception {

    // A person who types /media/x.woff2 into the address bar lands in the panel's not-found page.
    this.assertIsFallback("/media/remixicon-A1B2C3.woff2", NAVIGATION_ACCEPT);
  }

  @Test
  @DisplayName("an Accept that cannot be parsed is not a navigation")
  void garbageAcceptIsNotANavigation() throws Exception {

    this.assertIsNotFoundJson("/media/font.woff2", "not a media type");
    this.assertIsFallback("/repositories/repo", "not a media type");
  }

  @Test
  @DisplayName("the files of the build are served as themselves, with validators")
  void bundledFilesAreServed() throws Exception {

    this.request("/assets/remixicon-A1B2C3.woff2", "*/*")
        .andExpect(status().isOk())
        .andExpect(content().string(FONT_BYTES))
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("font/woff2")))
        .andExpect(header().exists(HttpHeaders.LAST_MODIFIED));
    this.request("/assets/chunk-Q1W2E3.js", "*/*")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("javascript")));
    this.request("/assets/logo.svg", "image/svg+xml,*/*")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/svg+xml")));
    // The root-level files (a dotted first segment is not the SPA controller's).
    this.request("/main-ABC123.js", "*/*")
        .andExpect(status().isOk())
        .andExpect(content().string("console.log('panel');"))
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("javascript")));
    this.request("/styles-XYZ789.css", "text/css,*/*;q=0.1")
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/css")));
  }

  @Test
  @DisplayName("a served file answers a conditional request with 304 and Last-Modified/ETag")
  void bundledFilesAnswerConditionalRequests() throws Exception {

    final var first = this.request("/assets/remixicon-A1B2C3.woff2", "*/*").andReturn();
    final var lastModified = first.getResponse().getHeader(HttpHeaders.LAST_MODIFIED);
    assertThat(lastModified).isNotBlank();

    this.perform(
            get("/assets/remixicon-A1B2C3.woff2")
                .header(HttpHeaders.IF_MODIFIED_SINCE, lastModified))
        .andExpect(status().isNotModified());
  }

  @Test
  @DisplayName("a missing file under /assets/ or at the root is a 404 that is not HTML")
  void missingFilesOutsideTheFallbackAreNotFound() throws Exception {

    this.assertIsNotFoundJson("/assets/missing.js", "*/*");
    this.assertIsNotFoundJson("/assets/deep/missing.woff2", "*/*");
    this.assertIsNotFoundJson("/main-DOESNOTEXIST.js", "*/*");
  }

  @Test
  @DisplayName("a file under a directory other than assets/ is a 404, not index.html")
  void fileOutsideAssetsIsNotServedAndNotFallenBack() throws Exception {

    // The controller matches before the resource handler; RPS-1445 put the fonts here and got
    // index.html. The build now writes to assets/ (angular.json outputPath.media); if it ever
    // emits elsewhere again, the icons fail loudly instead of parsing a page as a font.
    this.assertIsNotFoundJson("/media/stray.woff2", "*/*");
  }

  @Test
  @DisplayName("the API is not shadowed: an unknown API path is the API's own 404")
  void apiPathsAreNotTheFallback() throws Exception {

    this.request("/api/no-such-endpoint", "*/*").andExpect(forwardedUrl(null));
    this.request("/api/no-such-file.js", "*/*").andExpect(forwardedUrl(null));
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("path traversal never reaches a file outside the static root")
  @ValueSource(
      strings = {
        "/assets/../../secret.txt",
        "/assets/%2e%2e/%2e%2e/secret.txt",
        "/assets/..%2f..%2fsecret.txt",
        "/media/../../secret.txt",
        "/media/..%2f..%2fsecret.txt",
        "/%2e%2e/secret.txt",
        "/..%2fsecret.txt",
        "/assets/..;/..;/secret.txt",
        "/assets/%252e%252e/%252e%252e/secret.txt",
        "/media/..%5C..%5Csecret.txt",
        "/assets/....//....//secret.txt",
        "/file:/etc/passwd.txt"
      })
  void pathTraversalDoesNotLeak(final String uri) throws Exception {

    for (final var accept : List.of("*/*", NAVIGATION_ACCEPT)) {
      final MvcResult result = this.request(uri, accept).andReturn();

      assertThat(result.getResponse().getContentAsString())
          .as("%s must not return a file above the static root", uri)
          .doesNotContain(SECRET)
          .doesNotContain("root:");
      final var status = result.getResponse().getStatus();
      if (status == 200) {
        // Only ever the panel's own page (a navigation), never a file.
        assertThat(result.getResponse().getForwardedUrl()).isEqualTo("/index.html");
      } else {
        assertThat(status).as("%s", uri).isBetween(400, 499);
      }
    }
  }

  @Test
  @DisplayName("a request to the fallback is a GET only")
  void onlyGetFallsBack() throws Exception {

    this.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/repositories/repo"))
        .andExpect(forwardedUrl(null));
  }

  @Test
  @DisplayName("index.html itself is served as HTML")
  void indexIsServed() throws Exception {

    this.request("/index.html", NAVIGATION_ACCEPT)
        .andExpect(status().isOk())
        .andExpect(content().string(INDEX_HTML))
        .andExpect(
            header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.TEXT_HTML_VALUE)));
  }
}
