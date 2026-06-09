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
package io.repsy.os.server.protocols.helm.protocol.handlers.classic;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.protocol.handlers.classic.AbstractHelmChartPushProtocolMethodHandler;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class HelmChartMuseumPushProtocolMethodHandler
    extends AbstractHelmChartPushProtocolMethodHandler<UUID> {

  public HelmChartMuseumPushProtocolMethodHandler(
      @Qualifier("osHelmChartMuseumPathParser") final PathParser pathParser,
      final HelmFacade<UUID> helmProtocolTxFacade,
      final HelmProtocolProvider provider) {
    super(pathParser, helmProtocolTxFacade, provider);
  }
}