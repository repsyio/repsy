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
import { DialogDirective } from '../../../../shared/directives/dialog.directive';
import { getRepoDomain } from '../docker-repo-util';

@Component({
  selector: 'app-docker-config',
  standalone: true,
  imports: [DialogDirective, MarkdownComponent],
  styleUrl: './docker-config.component.css',
  templateUrl: './docker-config.component.html',
})
export class DockerConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() imageName = '<image_name>';
  @Input() tagName = '<tag_name>';
  @Input() platform = '<platform_name>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (
      changes['baseUrl'] ||
      changes['username'] ||
      changes['repoName'] ||
      changes['imageName'] ||
      changes['tagName'] ||
      changes['platform'] ||
      changes['deployToken']
    ) {
      this.updateMarkdown();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    const auth = this.deployToken ? this.getDeployTokenMarkdown() : this.getUsernamePasswordAuthMarkdown();
    this.markdown = auth + this.getKubernetesMarkdown();
  }

  // Pulling from a cluster (RPS-341): the same credentials as `docker login`, stored as an image pull secret.
  private getKubernetesMarkdown(): string {
    const password = this.deployToken ? '<repsy_deploy_token>' : '<password>';
    const tokenNote = this.deployToken ? '\nA **Read Only** deploy token is enough for pulling.\n' : '';
    return `
### Kubernetes

To let a cluster pull from this registry, store the credentials in an image pull secret
(add \`-n <namespace>\` to create it in the namespace of the workload);

\`\`\`bash
kubectl create secret docker-registry repsy-registry \\
  --docker-server=${getRepoDomain()} \\
  --docker-username=${this.username} \\
  --docker-password=${password}
\`\`\`
${tokenNote}
Then reference the secret from the pod spec;

\`\`\`yaml
spec:
  imagePullSecrets:
    - name: repsy-registry
  containers:
    - name: ${this.imageName}
      image: ${getRepoDomain()}/${this.repoName}/${this.imageName}:${this.tagName}
\`\`\`
`;
  }

  private getDeployTokenAuthStr(): string {
    return ` -u ${this.username} -p <repsy_deploy_token>`;
  }

  private getDeployTokenMarkdown(): string {
    return `
If you haven't already, log in.

\`\`\`bash
docker login ${getRepoDomain()}
\`\`\`

You may use any username, but make sure to enter your Repsy deploy token as the password;

or

\`\`\`bash
docker login ${getRepoDomain()} ${this.getDeployTokenAuthStr()}
\`\`\`

Then pull the image.

\`\`\`bash
docker pull ${getRepoDomain()}/${this.repoName}/${this.imageName}:${this.tagName}
\`\`\`
or
\`\`\`bash
docker pull --platform ${this.platform} ${getRepoDomain()}/${this.repoName}/${this.imageName}:${this.tagName}
\`\`\`

You're all set up and ready to go!
`;
  }

  private getUsernamePasswordAuthMarkdown(): string {
    return `
If you haven't already, log in;

\`\`\`bash
docker login ${getRepoDomain()}
\`\`\`

Then pull the image;

\`\`\`bash
docker pull ${getRepoDomain()}/${this.repoName}/${this.imageName}:${this.tagName}
\`\`\`

or You can pull the image for the specified platform;

\`\`\`bash
docker pull --platform ${this.platform} ${getRepoDomain()}/${this.repoName}/${this.imageName}:${this.tagName}
\`\`\`

You're all set up and ready to go!
`;
  }
}
