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
package io.repsy.os.server.protocols.cargo.shared.crate.storage;

import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.cargo.shared.storage.services.AbstractCargoStorageService;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@NullMarked
public class CargoStorageService extends AbstractCargoStorageService {

  public CargoStorageService(
      @Qualifier("osStorageStrategyCargo") final StorageStrategy storageStrategy) {

    super(storageStrategy);
  }
}
