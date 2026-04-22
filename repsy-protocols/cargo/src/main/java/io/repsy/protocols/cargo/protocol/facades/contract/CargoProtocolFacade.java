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
package io.repsy.protocols.cargo.protocol.facades.contract;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@NullMarked
public interface CargoProtocolFacade {

  List<CrateIndexEntry> getIndexEntries(ProtocolContext context);

  Resource download(ProtocolContext context);

  void publish(ProtocolContext context, InputStream inputStream) throws IOException;

  void yank(ProtocolContext context);

  void unyank(ProtocolContext context);

  Page<CrateListItem> search(ProtocolContext context, String query, Pageable pageable);
}
