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
import { CommonModule, NgOptimizedImage } from '@angular/common';
import { Component, OnDestroy } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Highlight } from 'ngx-highlightjs';
import { HighlightLineNumbers } from 'ngx-highlightjs/line-numbers';
import { Subscription } from 'rxjs';

import { SpinnerComponent } from '../../../../../../shared/components/spinner/spinner.component';
import { CopyClipboardComponent } from '../../../../../shared/components/copy-clipboard/copy-clipboard.component';
import { DangerModalService } from '../../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../../shared/dto/repo/repo-permission-info';
import { CrateInfo } from '../../dto/crate-info';
import { CrateDependencyInfo, CrateVersionInfo } from '../../dto/crate-version-info';
import { CargoService } from '../../service/cargo.service';

@Component({
  selector: 'app-cargo-crates-version-detail',
  standalone: true,
  imports: [CommonModule, SpinnerComponent, CopyClipboardComponent, NgOptimizedImage, HighlightLineNumbers, Highlight],
  templateUrl: './cargo-crates-version-detail.component.html',
})
export class CargoCratesVersionDetailComponent implements OnDestroy {
  public loading = true;
  public error: string;
  public packageName: string;
  public versionName: string;
  public addDependencyCommand: string;
  public installBinaryCommand: string;
  public cargoToml = '';
  public activeRepo: RepoPermissionInfo;
  public crate: CrateInfo;
  public crateVersion: CrateVersionInfo;
  private readonly repositoryChanges$: Subscription;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly cargoService: CargoService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.activeRepo = new RepoPermissionInfo();
    this.repositoryChanges$ = this.cargoService.repoChanges.subscribe((repo: RepoPermissionInfo) => {
      if (repo) {
        this.activeRepo = Object.assign(new RepoPermissionInfo(), repo);
        this.loadVersion();
      }
    });
  }

  public ngOnDestroy(): void {
    this.repositoryChanges$.unsubscribe();
  }

  public loadVersion(): void {
    const crateName = this.route.snapshot.paramMap.get('crate');
    const version = this.route.snapshot.paramMap.get('version');
    if (!crateName || !version) {
      this.loading = false;
      return;
    }
    this.packageName = crateName;
    this.versionName = version;
    this.addDependencyCommand = `cargo add ${crateName}@${version} --registry repsy`;
    this.installBinaryCommand = `cargo install ${crateName} --version ${version} --registry repsy`;

    this.loading = true;
    this.cargoService
      .fetchCrate(crateName)
      .then((crate) => {
        this.crate = crate;
        return this.cargoService.fetchCrateVersion(crateName, version);
      })
      .then((crateVersion) => {
        this.crateVersion = crateVersion;
        this.cargoToml = this.buildCargoToml(this.crate, crateVersion);
        this.error = null;
      })
      .catch((err: string) => {
        this.error = err;
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
      });
  }

  public deleteVersion(): void {
    this.dangerModalService.show('Delete Version', 'Delete', () => {
      this.loading = true;
      this.cargoService
        .deleteCrateVersion(this.packageName, this.versionName)
        .then(() => {
          this.router.navigateByUrl(`/${this.activeRepo.repoName}`).then(() => {
            this.toastService.show('Version deleted successfully', 'success');
          });
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private buildCargoToml(crate: CrateInfo, crateVersion: CrateVersionInfo): string {
    const deps = crateVersion.deps ?? [];
    const formatDep = (dep: CrateDependencyInfo): string => {
      const versionReq = dep.req || '*';
      const key = dep.package || dep.name;
      const extras: string[] = [];

      if (dep.features?.length) {
        extras.push(`features = [${dep.features.map((feature) => `"${feature}"`).join(', ')}]`);
      }
      if (dep.optional) {
        extras.push('optional = true');
      }
      if (dep.default_features === false) {
        extras.push('default-features = false');
      }
      if (dep.target) {
        extras.push(`target = "${dep.target}"`);
      }

      if (extras.length === 0) {
        return `${key} = "${versionReq}"`;
      }

      return `${key} = { version = "${versionReq}", ${extras.join(', ')} }`;
    };

    const normalDeps = deps.filter((dep) => dep.kind == null || dep.kind === 'normal').map(formatDep);
    const devDeps = deps.filter((dep) => dep.kind === 'dev').map(formatDep);
    const buildDeps = deps.filter((dep) => dep.kind === 'build').map(formatDep);

    const packageLines: string[] = [
      '[package]',
      `name = "${crate?.original_name || crateVersion.name}"`,
      `version = "${crateVersion.version}"`,
    ];
    if (crate?.description) {
      packageLines.push(`description = "${crate.description}"`);
    }
    if (crateVersion.license) {
      packageLines.push(`license = "${crateVersion.license}"`);
    }
    if (crateVersion.documentation) {
      packageLines.push(`documentation = "${crateVersion.documentation}"`);
    }
    if (crate?.homepage) {
      packageLines.push(`homepage = "${crate.homepage}"`);
    }
    if (crate?.repository) {
      packageLines.push(`repository = "${crate.repository}"`);
    }
    if (crateVersion.rust_version) {
      packageLines.push(`rust-version = "${crateVersion.rust_version}"`);
    }
    if (crateVersion.edition) {
      packageLines.push(`edition = "${crateVersion.edition}"`);
    }
    if (crate?.authors?.length) {
      packageLines.push(`authors = [${crate.authors.map((author) => `"${author}"`).join(', ')}]`);
    }

    const sections: string[] = [packageLines.join('\n')];
    sections.push(normalDeps.length ? ['[dependencies]', ...normalDeps].join('\n') : '[dependencies]\n# none');
    if (devDeps.length) {
      sections.push(['[dev-dependencies]', ...devDeps].join('\n'));
    }
    if (buildDeps.length) {
      sections.push(['[build-dependencies]', ...buildDeps].join('\n'));
    }

    return sections.join('\n\n');
  }
}
