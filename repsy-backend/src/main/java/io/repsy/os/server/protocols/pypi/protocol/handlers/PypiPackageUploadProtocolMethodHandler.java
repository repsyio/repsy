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
package io.repsy.os.server.protocols.pypi.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.protocols.pypi.protocol.PypiProtocolProvider;
import io.repsy.protocols.pypi.protocol.handlers.AbstractPypiPackageUploadProtocolMethodHandler;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class PypiPackageUploadProtocolMethodHandler
    extends AbstractPypiPackageUploadProtocolMethodHandler<UUID> {

  public PypiPackageUploadProtocolMethodHandler(
      @Qualifier("osPypiPathParser") final PathParser basePathParser,
      final PypiProtocolFacadeImpl pypiProtocolFacade,
      final PypiProtocolProvider provider) {

    super(basePathParser, pypiProtocolFacade, provider);
  }
}
