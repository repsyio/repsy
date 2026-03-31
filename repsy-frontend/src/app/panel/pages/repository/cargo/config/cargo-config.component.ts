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
Configure Cargo to use this registry:

\`\`\`toml
[registries]
repsy = { index = "sparse+${this.baseUrl}/cargo/${this.repoName}/" }
\`\`\`

Authenticate:

\`\`\`bash
cargo login --registry repsy ${this.deployToken ? '<YOUR_DEPLOY_TOKEN>' : '<YOUR_PASSWORD>'}
\`\`\`

Publish:

\`\`\`bash
cargo publish --registry repsy
\`\`\`

Quick validation checklist:

\`\`\`bash
# 1) Search in registry index
cargo search <crate_name> --registry repsy

# 2) Read crate metadata
cargo info <crate_name> --registry repsy

# 3) Library crate flow (most crates)
cargo add <crate_name>@<version> --registry repsy

# 4) Binary crate flow (only if crate defines [[bin]])
cargo install <crate_name> --version <version> --registry repsy
\`\`\`
`;
  }
}
