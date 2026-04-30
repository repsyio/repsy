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
    this.markdown = `
**Configure NuGet source:**

\`\`\`bash
dotnet nuget add source "${this.baseUrl}/${this.repoName}/v3/index.json" --name repsy --username ${this.username} --password <YOUR_DEPLOY_TOKEN> --store-password-in-clear-text
\`\`\`

**Push package:**

\`\`\`bash
dotnet nuget push <PACKAGE_PATH>.nupkg --source repsy --api-key <YOUR_DEPLOY_TOKEN>
\`\`\`
`;
  }
}
