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
package io.repsy.os.server.protocols.golang.protocol.facades;

import io.repsy.os.server.protocols.golang.shared.go_module.services.GoModuleService;
import io.repsy.os.server.protocols.golang.shared.storage.services.GoStorageService;
import io.repsy.protocols.golang.protocol.facades.AbstractGoProtocolFacade;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
public class GoProtocolFacade extends AbstractGoProtocolFacade<UUID> {

  public GoProtocolFacade(
      final GoStorageService golangStorageService,
      final GoModuleService goModuleService,
      @Value("${repsy.golang.max-module-zip-size:500MB}") final DataSize maxModuleZipSize) {

    super(golangStorageService, goModuleService, maxModuleZipSize.toBytes());
  }
}
