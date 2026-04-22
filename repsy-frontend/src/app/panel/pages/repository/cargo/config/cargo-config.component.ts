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
  selector: 'app-cargo-config',
  standalone: true,
  imports: [MarkdownComponent],
  styleUrl: './cargo-config.component.css',
  templateUrl: './cargo-config.component.html',
})
export class CargoConfigComponent implements OnInit, OnChanges {
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
    if (changes['baseUrl'] || changes['username'] || changes['repoName'] || changes['deployToken']) {
      this.updateMarkdown();
    }
  }

  public closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    this.markdown = `
**Configure Cargo to use this registry:**

Create or edit \`$HOME/.cargo/config.toml\` file

\`\`\`toml
[registries]
repsy = { index = "sparse+${this.baseUrl}/${this.repoName}/" }

# Public repo (download only): Skip the [registry] section below.
# If you previously used this repo as private, clear your Cargo cache first.

[registry]
global-credential-providers = ["cargo:token"]
\`\`\`

**Authenticate:**

You should use a Deploy Token. If you do not have one, go to \`settings > Deploy Tokens\` to create it.

\`\`\`bash
cargo login --registry repsy <YOUR_DEPLOY_TOKEN>
\`\`\`

**Publish:**

\`\`\`bash
cargo publish --registry repsy
\`\`\`

If you have uncommitted changes in your project but want to proceed with the publishing process,
you can use the \`--allow-dirty\` flag.

\`\`\`bash
cargo publish --registry repsy --allow-dirty
\`\`\`
`;
  }
}
