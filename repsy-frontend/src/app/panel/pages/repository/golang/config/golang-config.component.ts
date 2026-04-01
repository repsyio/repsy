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

import { environment } from '../../../../../../environments/environment';
import { MarkdownComponent } from '../../../../shared/components/markdown/markdown.component';

@Component({
  selector: 'app-golang-config',
  standalone: true,
  imports: [MarkdownComponent],
  templateUrl: './golang-config.component.html',
  styleUrl: './golang-config.component.css',
})
export class GolangConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  public markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['username'] || changes['repoName'] || changes['deployToken']) {
      this.updateMarkdown();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    this.markdown = this.getConfigMarkdown();
  }

  private getConfigMarkdown(): string {
    const password = this.deployToken ? 'YOUR_DEPLOY_TOKEN' : 'YOUR_PASSWORD';
    const repoUrl = `${environment.repoBaseUrl}/${this.repoName}`;
    const repoUrlWithAuth = this.deployToken
      ? `${environment.repoBaseUrl.replace(/^(https?):\/\//, `$1://token:${password}@`)}/${this.repoName}`
      : `${environment.repoBaseUrl.replace(/^(https?):\/\//, `$1://${this.username}:${password}@`)}/${this.repoName}`;

    return `
### 1. Configure GOPROXY

\`\`\`bash
go env -w GOPROXY="${repoUrlWithAuth},off"
\`\`\`

> Use \`,off\` as the fallback so Go fails loudly if a module is not in this registry instead of falling back to the internet.

### 2. Disable checksum database for private modules

\`\`\`bash
go env -w GONOSUMDB="*"
\`\`\`

> \`GONOSUMDB\` accepts module path prefixes (e.g. \`corp.internal\`) or \`*\` to disable for all modules. Do **not** set \`GOPRIVATE\` or \`GONOPROXY\` — they cause Go to skip the proxy and attempt VCS discovery directly.

### 3. Download a module

\`\`\`bash
go get corp.internal/yourmodule@v1.0.0
\`\`\`

---

### Publishing a module

Upload the \`.mod\` file first, then the \`.zip\`. Use \`-T\` (not \`-F\` or \`--data-binary\`):

\`\`\`bash
MODULE="corp.internal/yourmodule"
VERSION="v1.0.0"

curl -sf -u ${this.username}:${password} \\
  -T go.mod \\
  "${repoUrl}/\${MODULE}/@v/\${VERSION}.mod"

curl -sf -u ${this.username}:${password} \\
  -T "\${MODULE}@\${VERSION}.zip" \\
  "${repoUrl}/\${MODULE}/@v/\${VERSION}.zip"
\`\`\`

**Zip format rules:**
- All entries must be prefixed with \`modulepath@version/\`
- No empty directories — add files only
- Use individual file arguments, not \`zip -r\`:

\`\`\`bash
zip module.zip \\
  "\${MODULE}@\${VERSION}/go.mod" \\
  "\${MODULE}@\${VERSION}/yourfile.go"
\`\`\`
`;
  }
}
