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
package io.repsy.protocols.pypi.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@UtilityClass
@NullMarked
public final class PackageUtils {
  private static final String MINIMUM_REQUIRED_PYTHON_VERSION = ">=2.4";

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private static final Set<String> PACKAGE_PARAMETERS =
      Set.of(
          "keywords",
          "platform",
          "classifiers",
          "supported_platform",
          "project_urls",
          "provides_dist",
          "obsoletes_dist",
          "requires_dist",
          "requires_external",
          "provides_extras");

  // https://www.python.org/dev/peps/pep-0503/#normalized-names
  public static String normalizePackageName(final String packageName) {

    return packageName.replaceAll("[-_.]+", "-").toLowerCase(Locale.getDefault());
  }

  static boolean isPackageNameNormalized(final String packageName) {

    return packageName.equals(PackageUtils.normalizePackageName(packageName));
  }

  public static Map<String, Object> parseMultipartUploadRequestParameters(
      final MultipartHttpServletRequest request) {

    final var parameterMap = new HashMap<String, Object>();

    for (final var parameters = request.getParameterNames(); parameters.hasMoreElements(); ) {

      final var parameter = parameters.nextElement();

      if (PackageUtils.isParameterValueIsArray(parameter)) {
        parameterMap.put(parameter, request.getParameterValues(parameter));
      } else {
        parameterMap.put(parameter, request.getParameter(parameter));
      }
    }

    return parameterMap;
  }

  public static PackageUploadForm parseUploadForm(final Map<String, Object> parameterMap) {

    try {

      final var parameterMapString = MAPPER.writeValueAsString(parameterMap);
      final var uploadForm = MAPPER.readValue(parameterMapString, PackageUploadForm.class);

      if (!org.springframework.util.StringUtils.hasText(uploadForm.getRequires_python())) {
        uploadForm.setRequires_python(MINIMUM_REQUIRED_PYTHON_VERSION);
      }

      return uploadForm;
    } catch (final Exception _) {
      throw new BadRequestException("badPackageMetadata");
    }
  }

  private static boolean isParameterValueIsArray(final String parameterName) {

    return PACKAGE_PARAMETERS.contains(parameterName);
  }
}
