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
    const ociHost = this.baseUrl ? this.baseUrl.replace(/^https?:\/\//, '') : '<host>';

    this.markdown = `
**Classic Helm (Chart Museum protocol):**

\`\`\`bash
# Add repository
helm repo add ${this.repoName} ${this.baseUrl}/${this.repoName}

# Authenticate (if private)
helm repo add ${this.repoName} ${this.baseUrl}/${this.repoName} \\
  --username ${this.username} --password <YOUR_PASSWORD_OR_DEPLOY_TOKEN>

# Update repository index
helm repo update

# Push a chart (requires helm-push plugin)
helm plugin install https://github.com/chartmuseum/helm-push
helm cm-push <chart>.tgz ${this.repoName}

# Install chart
helm install <release-name> ${this.repoName}/<chart>

# Pull chart
helm pull ${this.repoName}/<chart> --version <version>
\`\`\`

**OCI (Helm OCI protocol):**

\`\`\`bash
# Login
helm registry login ${ociHost} --username ${this.username} --password <YOUR_PASSWORD_OR_DEPLOY_TOKEN>

# Push chart
helm push <chart>.tgz oci://${ociHost}/${this.repoName}

# Pull chart
helm pull oci://${ociHost}/${this.repoName}/<chart> --version <version>

# Install chart
helm install <release-name> oci://${ociHost}/${this.repoName}/<chart> --version <version>
\`\`\`
`;
  }
}
