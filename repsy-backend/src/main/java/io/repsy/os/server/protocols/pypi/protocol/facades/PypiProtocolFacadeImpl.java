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
package io.repsy.os.server.protocols.pypi.protocol.facades;

import io.repsy.protocols.pypi.protocol.facades.AbstractPypiProtocolFacade;
import io.repsy.protocols.pypi.shared.python_package.services.PypiPackageService;
import io.repsy.protocols.pypi.shared.storage.services.PypiStorageService;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@NullMarked
public class PypiProtocolFacadeImpl extends AbstractPypiProtocolFacade<UUID> {

  public PypiProtocolFacadeImpl(
      final PypiStorageService<UUID> pypiStorageService,
      final PypiPackageService<UUID> pypiPackageService) {

    super(pypiStorageService, pypiPackageService);
  }
}
