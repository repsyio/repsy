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
  selector: 'app-ruby-config',
  standalone: true,
  imports: [MarkdownComponent],
  styleUrl: './ruby-config.component.css',
  templateUrl: './ruby-config.component.html',
})
export class RubyConfigComponent implements OnInit, OnChanges {
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
    const registryUrl = `${this.baseUrl}/${this.repoName}`;
    this.markdown = `
**Configure Bundler to use this registry:**

Add a source block to your \`Gemfile\`:

\`\`\`ruby
source "${registryUrl}" do
  gem "your_gem"
end
\`\`\`

**Authenticate:**

You should use a Deploy Token. If you do not have one, go to \`settings > Deploy Tokens\` to create it.

\`gem push\` reads credentials from \`~/.gem/credentials\`. The value must be pre-encoded as a Basic auth header:

\`\`\`bash
echo ":repsy: Basic $(echo -n '<username>:<DEPLOY_TOKEN>' | base64)" >> ~/.gem/credentials
chmod 600 ~/.gem/credentials
\`\`\`

For Bundler:

\`\`\`bash
bundle config ${registryUrl} <username>:<DEPLOY_TOKEN>
\`\`\`

**Publish:**

\`\`\`bash
gem push your_gem-1.0.0.gem --host ${registryUrl} --key repsy
\`\`\`

**Yank a version:**

\`\`\`bash
gem yank your_gem -v 1.0.0 --host ${registryUrl} --key repsy
\`\`\`
`;
  }
}
