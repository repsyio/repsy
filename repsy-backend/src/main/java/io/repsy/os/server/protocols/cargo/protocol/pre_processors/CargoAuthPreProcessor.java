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
package io.repsy.os.server.protocols.cargo.protocol.pre_processors;

import io.repsy.os.server.protocols.cargo.shared.auth.services.CargoAuthenticator;
import io.repsy.os.server.shared.auth.BasicOrBearerAuthPreProcessor;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import org.springframework.stereotype.Component;

/** Cargo: Basic or Bearer; the Cargo CLI sends its token without a scheme. */
@Component
public class CargoAuthPreProcessor extends BasicOrBearerAuthPreProcessor<CargoAuthenticator> {

  public CargoAuthPreProcessor(
      final CargoAuthenticator authenticator, final CargoProtocolProvider provider) {
    super(authenticator, provider);
  }

  @Override
  protected boolean acceptsBareToken() {
    return true;
  }
}
