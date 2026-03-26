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
package io.repsy.os.server.protocols.pypi.protocol.pre_processors;

import freemarker.template.TemplateException;
import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.pypi.shared.python_package.services.PypiPackageServiceImpl;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class PypiSimpleHandlerPreProcessor extends ProtocolProcessor {
  private static final int PRIORITY = 200;

  private static final Pattern SIMPLE_PATTERN = Pattern.compile("^/simple(?:/([^/]+))?/?$");
  private static final String METHOD_KEY = "method";
  private static final String METHOD_NAME = "simple";

  private final PypiProtocolProvider provider;

  private final PypiPackageServiceImpl pypiPackageService;

  @PostConstruct
  public void register() {

    this.provider.registerPreProcessor(this);
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    if (this.shouldSkip(properties)) {
      return ProcessorResult.next();
    }

    final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();

    final var matcher = SIMPLE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ProcessorResult.of(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }

    final var packageName = matcher.group(1); // Can be null for package list

    return this.process(context, packageName);
  }

  private ProcessorResult process(final ProtocolContext context, final String packageName) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    final var packageListResourceOpt = this.getPackageListResourceOpt(packageName, repoInfo);

    if (packageListResourceOpt.isPresent()) {
      return ProcessorResult.of(ResponseEntity.ok(packageListResourceOpt.get()));
    }

    return ProcessorResult.next();
  }

  private Optional<ByteArrayResource> getPackageListResourceOpt(
      final @Nullable String packageName, final RepoInfo repoInfo) {

    if (packageName != null) {
      return Optional.empty();
    }

    try {
      final var resource =
          this.pypiPackageService.getPackageList(repoInfo.getStorageKey(), repoInfo.getName());

      return Optional.of(resource);
    } catch (final TemplateException | IOException ex) {

      throw new ErrorOccurredException(ex);
    }
  }

  private boolean shouldSkip(final Map<String, Object> properties) {

    final var method = (String) properties.getOrDefault(METHOD_KEY, StringUtils.EMPTY);

    return StringUtils.isEmpty(method) || !method.equals(METHOD_NAME);
  }
}
