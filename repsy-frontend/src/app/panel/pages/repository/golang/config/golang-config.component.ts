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
  @Input() modulePath = '<module_path>';
  @Input() moduleVersion = '<version>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  public markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (
      changes['username'] ||
      changes['repoName'] ||
      changes['modulePath'] ||
      changes['moduleVersion'] ||
      changes['deployToken']
    ) {
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

### 2. Configure checksum database

Repsy does not implement a checksum database, so private modules cannot be verified against \`sum.golang.org\`.
Set \`GONOSUMDB\` to the module path prefix to skip checksum verification for private modules
while keeping it enabled for public ones:

\`\`\`bash
go env -w GONOSUMDB="${this.modulePath}"
\`\`\`

Or disable it for all modules:

\`\`\`bash
go env -w GONOSUMDB="*"
\`\`\`

> Do **not** set \`GOPRIVATE\` or \`GONOPROXY\` — they cause Go to skip the proxy and attempt VCS discovery directly.

### 3. Download a module

\`\`\`bash
go get ${this.modulePath}@${this.moduleVersion}
\`\`\`

---

### Publishing a module

Upload a single \`.zip\` containing the module source. The zip must include \`go.mod\` and all source files
prefixed with \`{modulePath}@{version}/\`.

**1. Build the zip:**

\`\`\`bash
VERSION=${this.moduleVersion}
MODULE_PATH=${this.modulePath}
STAGING=\$(mktemp -d)
MODULE_VERSION_DIR="\${STAGING}/\${MODULE_PATH}@\${VERSION}"

mkdir -p "\${MODULE_VERSION_DIR}"
cp -r . "\${MODULE_VERSION_DIR}/"
(cd "\${STAGING}" && find "\${MODULE_PATH}@\${VERSION}" -type f | xargs zip "\${OLDPWD}/module.zip")
\`\`\`

**2. Upload:**

\`\`\`bash
curl -u ${this.username}:${password} \\
  -T module.zip \\
  -H "Content-Sha256: \$(sha256sum module.zip | cut -d' ' -f1)" \\
  "${repoUrl}/\${MODULE_PATH}/@v/\${VERSION}.zip"
\`\`\`

> \`Content-Sha256\` is optional — if provided, the server verifies the checksum before saving. Omit the \`-H\` line to skip verification.
`;
  }
}
