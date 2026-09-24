///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { environment } from '../../../../../../environments/environment';
import { DockerConfigComponent } from './docker-config.component';

describe('DockerConfigComponent', () => {
  const originalRepoBaseUrl = environment.repoBaseUrl;

  afterEach(() => {
    environment.repoBaseUrl = originalRepoBaseUrl;
  });

  function render(deployToken: boolean): string {
    const component = new DockerConfigComponent();
    component.deployToken = deployToken;
    component.repoName = 'my-repo';
    component.imageName = 'app';
    component.tagName = 'v1';
    component.ngOnInit();

    return component.markdown;
  }

  [false, true].forEach((deployToken) => {
    it(`prints the registry host and port of the stack (deployToken=${deployToken})`, () => {
      environment.repoBaseUrl = 'http://localhost:15090';

      const markdown = render(deployToken);

      expect(markdown).toContain('docker login localhost:15090');
      expect(markdown).toContain('docker pull localhost:15090/my-repo/app:v1');
      expect(markdown).not.toContain('9090');
    });
  });
});
