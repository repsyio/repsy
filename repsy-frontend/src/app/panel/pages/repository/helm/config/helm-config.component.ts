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
  selector: 'app-helm-config',
  standalone: true,
  imports: [MarkdownComponent],
  templateUrl: './helm-config.component.html',
})
export class HelmConfigComponent implements OnInit, OnChanges {
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
    const classicUrl = `${this.baseUrl}/${this.repoName}`;
    const host = this.baseUrl.replace(/^https?:\/\//, '');

    this.markdown = `
**Classic Helm Repository:**

\`\`\`bash
helm repo add ${this.repoName} ${classicUrl} --username ${this.username} --password <YOUR_DEPLOY_TOKEN>
helm repo update
helm install ${this.repoName} ${this.repoName}/<chart_name> --version <version>
\`\`\`

**OCI Registry:**

\`\`\`bash
helm registry login ${host} --username ${this.username} --password <YOUR_DEPLOY_TOKEN>
helm push <chart>.tgz oci://${host}/${this.repoName}
helm pull oci://${host}/${this.repoName}/<chart_name> --version <version>
helm registry logout ${host}
\`\`\`

You should use a Deploy Token. If you do not have one, go to \`settings > Deploy Tokens\` to create it.
`;
  }
}
