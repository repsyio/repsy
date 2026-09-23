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

import { NpmConfigComponent } from './npm-config.component';

describe('NpmConfigComponent (RPS-1206)', () => {
  let component: NpmConfigComponent;

  beforeEach(() => {
    component = new NpmConfigComponent();
    component.baseUrl = 'https://repo.example.com';
    component.repoName = 'my-repo';
    component.username = 'alice';
    component.scopeName = '<scope_name>';
    component.deployToken = false;
  });

  it('renders the exact registry snippet, byte-for-byte, for a known repo/host', () => {
    component.ngOnInit();

    expect(component.markdown).toBe(`
Either public or private, you must authenticate to publish packages to your registry. Authentication is also
required to install packages from private registries;

\`\`\`bash
npm login --registry https://repo.example.com/my-repo/
\`\`\`



To use your registry only for scoped packages, for each different scope, you should configure npm to use your
registry for all operations related to that scope;

\`\`\`bash
npm config set <scope_name>:registry https://repo.example.com/my-repo/
\`\`\`

And to use your registry as the main registry;

\`\`\`bash
npm config set registry https://repo.example.com/my-repo/
\`\`\`

That is all, now you can start using your registry.`);
  });

  it('renders the exact deploy-token bypass snippet, byte-for-byte, with a host and path that match the registry line', () => {
    component.deployToken = true;

    component.ngOnInit();

    expect(component.markdown).toBe(`
Either public or private, you must authenticate to publish packages to your registry. Authentication is also
required to install packages from private registries;

\`\`\`bash
npm login --registry https://repo.example.com/my-repo/
\`\`\`


<small>***username:*** alice or ANY\\_USERNAME <br> ***password:*** YOUR\\_DEPLOY\\_TOKEN</small>

or you can bypass the login operation

**Bypass Login**

In automated processes like pipelines, interactive login sessions can be challenging.
Therefore, you can directly use your repsy deploy token as the npm authToken.

If the file does not already exist, create a ___.npmrc___ file in your home directory:

\`\`\`bash
touch $HOME/.npmrc
\`\`\`

Then, add the following line to your ___.npmrc___ file

\`\`\`bash
//repo.example.com/my-repo/:_authToken=<YOUR_DEPLOY_TOKEN>
\`\`\`


To use your registry only for scoped packages, for each different scope, you should configure npm to use your
registry for all operations related to that scope;

\`\`\`bash
npm config set <scope_name>:registry https://repo.example.com/my-repo/
\`\`\`

And to use your registry as the main registry;

\`\`\`bash
npm config set registry https://repo.example.com/my-repo/
\`\`\`

That is all, now you can start using your registry.`);
  });

  it('derives the auth-token host and path from the same baseUrl as the registry line, including a port', () => {
    component.baseUrl = 'http://localhost:9090';
    component.deployToken = true;

    component.ngOnInit();

    expect(component.markdown).toContain('npm login --registry http://localhost:9090/my-repo/');
    expect(component.markdown).toContain('npm config set registry http://localhost:9090/my-repo/');
    expect(component.markdown).toContain('//localhost:9090/my-repo/:_authToken=<YOUR_DEPLOY_TOKEN>');
    // The auth key's host:port/path segment must be a prefix that actually appears in the
    // registry URL, or npm's longest-prefix match in .npmrc never selects it (RPS-1206).
    const registryUrl = 'http://localhost:9090/my-repo/';
    const authHostAndPath = 'localhost:9090/my-repo/';
    expect(registryUrl.endsWith(authHostAndPath)).toBe(true);
  });

  it('updates the snippet when baseUrl or repoName change after init', () => {
    component.ngOnInit();

    component.baseUrl = 'https://other.example.com';
    component.repoName = 'other-repo';
    component.ngOnChanges({
      baseUrl: { currentValue: component.baseUrl, previousValue: '', firstChange: false, isFirstChange: () => false },
    });

    expect(component.markdown).toContain('npm login --registry https://other.example.com/other-repo/');
    expect(component.markdown).not.toContain('my-repo');
  });
});
