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

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.shared.utils.MultiPortNames;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The single-page-app fallback: a URL that is a client-side route of the Angular panel is answered
 * with {@code index.html}, so a reload or a shared deep link works.
 *
 * <p>This controller is matched before the static resource handler, so whatever it forwards is
 * never looked up as a file. A bundled file that the build put under a directory other than {@code
 * /assets/} (RPS-1445: the remixicon fonts under {@code /media/}) therefore came back as {@code
 * index.html} with a 200 and {@code text/html}, and the browser rejected it as a font. RPS-1467: a
 * path whose last segment has a static-asset extension ({@link #ASSET_EXTENSION}) is not a client
 * route to the panel's own subresource requests (fonts, scripts, styles and images are requested
 * with an {@code Accept} that never names {@code text/html}), so it is answered with a 404 instead
 * of a page that pretends to be the file.
 *
 * <p>The exception is a request that names {@code text/html} in {@code Accept}, which is a browser
 * navigating to the URL (a reload of a deep link): the panel's routes carry package names, group
 * ids and versions as their last segment, and an npm package such as {@code chart.js} or a version
 * such as {@code 1.0.0} is a legitimate route, so those still get {@code index.html}.
 */
@RestApiPort(MultiPortNames.PORT_API)
@Controller
public class SpaController {

  /**
   * The extensions of the files the panel build and its assets directory emit (scripts, styles,
   * source maps, fonts, images, text and manifest files).
   */
  private static final @NonNull Pattern ASSET_EXTENSION =
      Pattern.compile(
          "\\.(?:js|mjs|cjs|css|map|json|txt|xml|webmanifest|woff2?|ttf|otf|eot|svg|png|jpe?g|gif"
              + "|webp|avif|ico|wasm)$",
          Pattern.CASE_INSENSITIVE);

  @GetMapping(
      value = {
        "/",
        "/{path:^(?!api|assets|favicon\\.ico)[^\\.]*$}",
        "/{path:^(?!api|assets|favicon\\.ico)[^\\.]*$}/**"
      })
  public @NonNull String forward(
      @PathVariable(required = false) final @NonNull String path,
      final @NonNull HttpServletRequest request)
      throws NoResourceFoundException {

    final var pathWithinApplication =
        ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();

    if (ASSET_EXTENSION.matcher(lastSegment(pathWithinApplication)).find()
        && !isNavigation(request)) {
      throw new NoResourceFoundException(
          HttpMethod.GET, request.getContextPath(), pathWithinApplication.value());
    }

    return "forward:/index.html";
  }

  private static @NonNull String lastSegment(final @NonNull PathContainer path) {

    final List<PathContainer.Element> elements = path.elements();

    for (int i = elements.size() - 1; i >= 0; i--) {
      if (elements.get(i) instanceof PathContainer.PathSegment segment) {
        return segment.valueToMatch();
      }
    }

    return "";
  }

  /** True when the client asked for a page: {@code Accept} names {@code text/html} explicitly. */
  private static boolean isNavigation(final @NonNull HttpServletRequest request) {

    try {
      return MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)))
          .stream()
          .anyMatch(MediaType.TEXT_HTML::equalsTypeAndSubtype);
    } catch (final InvalidMediaTypeException e) {
      return false;
    }
  }
}
