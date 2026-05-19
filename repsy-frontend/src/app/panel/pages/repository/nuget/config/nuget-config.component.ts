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

import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';

import { MarkdownComponent } from '../../../../shared/components/markdown/markdown.component';

@Component({
  selector: 'app-nuget-config',
  standalone: true,
  imports: [MarkdownComponent],
  styleUrl: './nuget-config.component.css',
  templateUrl: './nuget-config.component.html',
})
export class NugetConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  public markdown: string;

  public ngOnInit(): void {
    this.updateMarkdown();
  }

  public ngOnChanges(changes: SimpleChanges): void {
    if (changes['baseUrl'] || changes['repoName'] || changes['deployToken']) {
      this.updateMarkdown();
    }
  }

  public closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    const sourceUrl = `${this.baseUrl}/${this.repoName}/v3/index.json`;
    this.markdown = `
**Option A — NuGet.Config (recommended)**

Add source to \`nuget.config\` in your project/solution root *(safe to commit)*:

\`\`\`xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <add key="repsy" value="${sourceUrl}" allowInsecureConnections="true" />
  </packageSources>
</configuration>
\`\`\`

Add credentials to \`~/.nuget/NuGet/NuGet.Config\` *(user-level — do not commit)*:

\`\`\`xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSourceCredentials>
    <repsy>
      <add key="Username" value="${this.username}" />
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
dotnet add package <PACKAGE_ID> --version <VERSION> --source repsy
\`\`\`

---

**Option B — Direct URL (no NuGet.Config needed)**

Push package:

\`\`\`bash
dotnet nuget push ./bin/Release/*.nupkg \\
  --source "${sourceUrl}" \\
  --api-key "<YOUR_PASSWORD_OR_DEPLOY_TOKEN>"
\`\`\`

Install package:

\`\`\`bash
dotnet add package <PACKAGE_ID> --version <VERSION> --source "${sourceUrl}"
\`\`\`
`;
  }
}
