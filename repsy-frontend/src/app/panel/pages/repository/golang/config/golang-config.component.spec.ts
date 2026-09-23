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
import { GolangConfigComponent } from './golang-config.component';

describe('GolangConfigComponent', () => {
  const originalRepoBaseUrl = environment.repoBaseUrl;

  let component: GolangConfigComponent;

  beforeEach(() => {
    component = new GolangConfigComponent();
    component.username = 'alice';
    component.repoName = 'go-repo';
    component.deployToken = true;
  });

  afterEach(() => {
    environment.repoBaseUrl = originalRepoBaseUrl;
  });

  describe('on an https deployment', () => {
    beforeEach(() => {
      environment.repoBaseUrl = 'https://repo.example.com';
    });

    it('renders a credentialed GOPROXY and no insecure-URL warning', () => {
      component.ngOnInit();

      expect(component.markdown).toContain('GOPROXY="https://token:YOUR_DEPLOY_TOKEN@repo.example.com/go-repo,off"');
      expect(component.markdown).not.toContain('requires HTTPS');
    });
  });

  describe('on a plain-http deployment', () => {
    beforeEach(() => {
      environment.repoBaseUrl = 'http://localhost:9090';
    });

    it('renders a credential-free GOPROXY and the insecure-URL warning', () => {
      component.ngOnInit();

      expect(component.markdown).toContain('GOPROXY="http://localhost:9090/go-repo,off"');
      expect(component.markdown).not.toContain('token:');
      expect(component.markdown).not.toContain('@localhost');
      expect(component.markdown).toContain('**A private Go repository requires HTTPS.**');
      expect(component.markdown).toContain('refusing to pass credentials to insecure URL');
    });

    it('still embeds credentials in the publish curl command, unaffected by the GOPROXY scheme guard', () => {
      component.ngOnInit();

      expect(component.markdown).toContain('curl -u alice:YOUR_DEPLOY_TOKEN');
    });
  });

  describe('with a malformed repoBaseUrl', () => {
    it('falls back to treating it as non-plain-http rather than throwing', () => {
      environment.repoBaseUrl = 'not-a-url';

      expect(() => component.ngOnInit()).not.toThrow();
      expect(component.markdown).not.toContain('requires HTTPS');
    });
  });
});
