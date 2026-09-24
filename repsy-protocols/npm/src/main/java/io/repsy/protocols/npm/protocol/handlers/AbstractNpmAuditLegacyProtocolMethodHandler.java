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
package io.repsy.protocols.npm.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.audit.NpmAdvisory;
import io.repsy.protocols.npm.shared.audit.NpmAdvisorySource;
import io.repsy.protocols.npm.shared.audit.NpmAuditReportBuilder;
import io.repsy.protocols.npm.shared.audit.NpmAuditTree;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /{repo}/-/npm/v1/security/audits} and {@code .../audits/quick}, which npm 6, npm 10
 * (as its fallback) and pnpm 9 and 10 call for {@code audit}. The body is the dependency tree of
 * the project and the answer is the legacy report of the advisories found in it.
 */
@NullMarked
public abstract class AbstractNpmAuditLegacyProtocolMethodHandler<ID>
    extends AbstractNpmAuditProtocolMethodHandler<ID> {

  public AbstractNpmAuditLegacyProtocolMethodHandler(
      @Qualifier("npmPathParser") final PathParser basePathParser,
      final NpmAdvisorySource<ID> advisorySource,
      final ObjectMapper objectMapper,
      final NpmProtocolProvider provider) {
    super(
        basePathParser,
        "/-/npm/v1/security/audits(?:/quick)?",
        advisorySource,
        objectMapper,
        provider);
  }

  @Override
  protected Object report(final BaseRepoInfo<ID> repoInfo, final JsonNode body) {

    final var tree = NpmAuditTree.parse(body);
    final var advisories =
        tree.versionsByName().isEmpty()
            ? List.<NpmAdvisory>of()
            : this.advisorySource().findAdvisories(repoInfo, tree.versionsByName());

    return NpmAuditReportBuilder.legacy(tree, advisories);
  }
}
