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
package io.repsy.os.server.protocols.helm.protocol.pre_processors;

import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.os.server.protocols.helm.shared.auth.HelmAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.oci.utils.OciErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * Helm (classic and OCI): Basic or Bearer; a request without credentials is answered in the OCI
 * error format on the OCI routes.
 */
@Component
@NullMarked
public class HelmAuthPreProcessor extends BasicOrBearerAuthPreProcessor<HelmAuthenticator> {

  private final RestResponseFactory resp;

  public HelmAuthPreProcessor(
      final HelmProtocolProvider provider,
      final RestResponseFactory resp,
      final HelmAuthenticator authenticator) {

    super(authenticator, provider);
    this.resp = resp;
  }

  @Override
  protected ProcessorResult missingCredential(final HttpServletRequest request) {
    return ProcessorResult.of(OciErrors.challenge(request, this.challenge(null), this.resp));
  }
}
