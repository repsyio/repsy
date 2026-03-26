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
import { getRepoDomain } from '../docker-repo-util';

@Component({
  selector: 'app-docker-config',
  standalone: true,
  imports: [MarkdownComponent],
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

  public markdown: string;

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
    if (this.deployToken) {
      this.markdown = this.getDeployTokenMarkdown();
    } else {
      this.markdown = this.getUsernamePasswordAuthMarkdown();
    }
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
