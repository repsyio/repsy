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
package io.repsy.protocols.nuget.protocol.facades.contract;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.nuget.shared.dtos.NuGetAutocompleteResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationIndexResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetRegistrationLeafResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.nuget.shared.dtos.NuGetServiceIndexResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
public interface NuGetProtocolFacade {

  void publish(ProtocolContext context, InputStream inputStream) throws IOException;

  void unlistVersion(ProtocolContext context) throws IOException;

  void relistVersion(ProtocolContext context) throws IOException;

  NuGetServiceIndexResponse getServiceIndex(ProtocolContext context, String baseUrl);

  NuGetSearchResponse search(
      ProtocolContext context, String q, int skip, int take, boolean prerelease, String baseUrl);

  NuGetAutocompleteResponse autocomplete(
      ProtocolContext context,
      String q,
      @Nullable String id,
      int skip,
      int take,
      boolean prerelease);

  NuGetRegistrationIndexResponse getRegistrationIndex(ProtocolContext context, String baseUrl);

  NuGetRegistrationLeafResponse getRegistrationLeaf(ProtocolContext context, String baseUrl);

  Resource downloadNuPackage(ProtocolContext context);

  Resource downloadNuspec(ProtocolContext context);

  List<String> getPackageVersions(ProtocolContext context);
}
