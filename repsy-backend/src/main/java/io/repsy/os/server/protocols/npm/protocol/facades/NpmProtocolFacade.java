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
package io.repsy.os.server.protocols.npm.protocol.facades;

import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.protocols.npm.protocol.facades.AbstractNpmProtocolFacade;
import io.repsy.protocols.npm.shared.npm_package.services.NpmPackageService;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class NpmProtocolFacade extends AbstractNpmProtocolFacade<UUID> {

  public NpmProtocolFacade(
      final NpmPackageService<UUID> npmPackageService, final NpmStorageService npmStorageService) {

    super(npmPackageService, npmStorageService);
  }
}
