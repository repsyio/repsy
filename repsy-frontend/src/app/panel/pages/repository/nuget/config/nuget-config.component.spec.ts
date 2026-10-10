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

import { NuGetConfigComponent } from './nuget-config.component';

describe('NuGetConfigComponent (RPS-1570)', () => {
  let component: NuGetConfigComponent;

  beforeEach(() => {
    component = new NuGetConfigComponent();
    component.baseUrl = 'https://repo.example.com';
    component.repoName = 'my-repo';
    component.username = 'alice';
    component.deployToken = false;
  });

  it('renders the exact snippet, byte-for-byte, for a known repo/host', () => {
    component.ngOnInit();

    expect(component.markdown).toBe(`
**Option A — NuGet.Config (recommended)**

Add source to \`NuGet.Config\` in your project/solution root *(safe to commit)*:

\`\`\`xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="repsy" value="https://repo.example.com/my-repo/v3/index.json" allowInsecureConnections="true" />
  </packageSources>
</configuration>
\`\`\`

Add credentials to \`~/.nuget/NuGet/NuGet.Config\` *(user-level — do not commit)*:

\`\`\`xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSourceCredentials>
    <repsy>
      <add key="Username" value="alice" />
      <add key="ClearTextPassword" value="<YOUR_PASSWORD_OR_DEPLOY_TOKEN>" />
    </repsy>
  </packageSourceCredentials>
</configuration>
\`\`\`

Push package:

\`\`\`bash
dotnet nuget push ./bin/Release/*.nupkg --source repsy --api-key any
\`\`\`

Install package:

\`\`\`bash
dotnet add package <PACKAGE_ID> --version <VERSION>
\`\`\`

\`dotnet add package --source\` only accepts a URL or a folder, never the name of a configured
source, so once \`repsy\` is added to \`NuGet.Config\` you install without \`--source\` at all.

---

**Option B — Direct URL (no NuGet.Config needed)**

Push package:

\`\`\`bash
dotnet nuget push ./bin/Release/*.nupkg \\
  --source "https://repo.example.com/my-repo/v3/index.json" \\
  --api-key "<YOUR_DEPLOY_TOKEN>"
\`\`\`

\`--api-key\` only accepts a deploy token, not your account password. To push with a password
instead, use Option A, whose \`NuGet.Config\` credentials support both.

Install package:

\`\`\`bash
dotnet add package <PACKAGE_ID> --version <VERSION> --source "https://repo.example.com/my-repo/v3/index.json"
\`\`\`
`);
  });

  it('never shows the install command with a bare source name, only unqualified or with the full URL', () => {
    // dotnet add package --source only accepts a URL or a folder, never a configured source's
    // name (RPS-1570): "--source repsy" is not a valid invocation and must not appear anywhere.
    component.ngOnInit();

    expect(component.markdown).not.toContain('add package <PACKAGE_ID> --version <VERSION> --source repsy');
    expect(component.markdown).toContain('dotnet add package <PACKAGE_ID> --version <VERSION>\n');
    expect(component.markdown).toContain(
      'dotnet add package <PACKAGE_ID> --version <VERSION> --source "https://repo.example.com/my-repo/v3/index.json"',
    );
    // The push commands legitimately use the named source: `dotnet nuget push --source` does
    // resolve a configured source's name.
    expect(component.markdown).toContain('dotnet nuget push ./bin/Release/*.nupkg --source repsy --api-key any');
  });

  it('updates the snippet when baseUrl or repoName change after init', () => {
    component.ngOnInit();

    component.baseUrl = 'https://other.example.com';
    component.repoName = 'other-repo';
    component.ngOnChanges({
      baseUrl: { currentValue: component.baseUrl, previousValue: '', firstChange: false, isFirstChange: () => false },
    });

    expect(component.markdown).toContain('https://other.example.com/other-repo/v3/index.json');
    expect(component.markdown).not.toContain('my-repo');
  });
});
