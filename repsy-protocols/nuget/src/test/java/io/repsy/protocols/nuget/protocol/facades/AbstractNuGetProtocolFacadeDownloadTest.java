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
package io.repsy.protocols.nuget.protocol.facades;

import static io.repsy.protocols.nuget.NuGetTestContexts.context;
import static io.repsy.protocols.nuget.NuGetTestContexts.repoInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.storage.RepoRef;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractNuGetProtocolFacade .nupkg download and lookup (RPS-1465)")
class AbstractNuGetProtocolFacadeDownloadTest {

  private static final String PATH = "/v3/package/Some.Package/1.0.0/some.package.1.0.0.nupkg";

  @Mock private NuGetStorageService storageService;
  @Mock private NuGetPackageService<UUID> packageService;

  private static class TestFacade extends AbstractNuGetProtocolFacade<UUID> {

    TestFacade(final NuGetStorageService s, final NuGetPackageService<UUID> p) {
      super(s, p);
    }
  }

  @Test
  @DisplayName("getNuPackage() resolves the file and counts no download")
  void getNuPackageCountsNothing() {
    final var resource = new ByteArrayResource(new byte[] {1});
    final var repoInfo = repoInfo();
    final var ctx = context(PATH, repoInfo);
    when(this.storageService.getNuPkg(RepoRef.of(repoInfo), "Some.Package", "1.0.0"))
        .thenReturn(resource);

    final var result = new TestFacade(this.storageService, this.packageService).getNuPackage(ctx);

    assertThat(result).isSameAs(resource);
    verifyNoInteractions(this.packageService);
  }

  @Test
  @DisplayName("downloadNuPackage() resolves the same file and counts the download")
  void downloadNuPackageCounts() {
    final var resource = new ByteArrayResource(new byte[] {1});
    final var repoInfo = repoInfo();
    final var ctx = context(PATH, repoInfo);
    when(this.storageService.getNuPkg(RepoRef.of(repoInfo), "Some.Package", "1.0.0"))
        .thenReturn(resource);

    final var result =
        new TestFacade(this.storageService, this.packageService).downloadNuPackage(ctx);

    assertThat(result).isSameAs(resource);
    verify(this.packageService).incrementDownloadCount(repoInfo, "Some.Package", "1.0.0");
  }
}
