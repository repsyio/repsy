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

  function render(deployToken: boolean, username?: string): string {
    const component = new DockerConfigComponent();
    component.deployToken = deployToken;
    if (username !== undefined) {
      component.username = username;
    }
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

    it(`appends a Kubernetes image pull section after the pull flow (deployToken=${deployToken})`, () => {
      environment.repoBaseUrl = 'http://localhost:15090';

      const markdown = render(deployToken);

      expect(markdown).toContain('### Kubernetes');
      expect(markdown).toContain('kubectl create secret docker-registry repsy-registry');
      expect(markdown).toContain('--docker-server=localhost:15090');
      expect(markdown).toContain('imagePullSecrets:');
      expect(markdown).toContain('image: localhost:15090/my-repo/app:v1');
      expect(markdown.indexOf('### Kubernetes')).toBeGreaterThan(markdown.indexOf('docker pull localhost:15090'));
    });
  });

  it('uses a password placeholder that never spells the deploy token marker', () => {
    environment.repoBaseUrl = 'http://localhost:15090';

    const markdown = render(false);

    expect(markdown).toContain('--docker-password=<password>');
    expect(markdown).not.toContain('<repsy_deploy_token>');
  });

  it('uses the deploy token and its username in the Kubernetes secret', () => {
    environment.repoBaseUrl = 'http://localhost:15090';

    const markdown = render(true, 'ci');

    expect(markdown).toContain('--docker-username=ci');
    expect(markdown).toContain('--docker-password=<repsy_deploy_token>');
    expect(markdown).toContain('Read Only');
    expect(markdown).not.toContain('<password>');
  });

  it('keeps the username placeholder in the Kubernetes secret when no username is set', () => {
    environment.repoBaseUrl = 'http://localhost:15090';

    const markdown = render(false);

    expect(markdown).toContain('--docker-username=<username>');
  });
});
