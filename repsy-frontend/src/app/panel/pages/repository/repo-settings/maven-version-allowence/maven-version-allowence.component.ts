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

import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { SelectorComponent } from '../../../../shared/components/selector/selector.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoSupport } from '../../../../shared/dto/repo/repo-type';
import { MavenRepoSettingsForm } from '../../maven/dto/maven-repo-settings-form';
import { MavenService } from '../../maven/service/maven.service';

@Component({
  selector: 'app-maven-version-allowence',
  templateUrl: './maven-version-allowence.component.html',
  styleUrls: ['./maven-version-allowence.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, SelectorComponent, RouterLink],
})
export class VersionAllowenceComponent implements OnInit {
  @Input() public parentForm: FormGroup;
  @Output() public fetch = new EventEmitter<void>();

  public selectedOption: RepoSupport = RepoSupport.ALL;
  public repoOptions = [RepoSupport.ALL, RepoSupport.SNAPSHOTS, RepoSupport.RELEASES];

  constructor(
    private readonly mavenService: MavenService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    const snapshots = this.parentForm.get('snapshots')!.value;
    const releases = this.parentForm.get('releases')!.value;
    this.selectedOption = this.whichVersionAllowence(snapshots, releases);
  }

  public selectType(option: string) {
    const form: MavenRepoSettingsForm = new MavenRepoSettingsForm();

    form.privateRepo = this.parentForm.get('privateRepository')!.value;
    form.snapshots = option === RepoSupport.SNAPSHOTS || option === RepoSupport.ALL;
    form.releases = option === RepoSupport.RELEASES || option === RepoSupport.ALL;
    form.allowOverride = this.parentForm.get('allowOverride')!.value;

    this.mavenService
      .updateRepoSettings(form)
      .then(() => {
        this.fetch.emit();
        this.toastService.show(`Version allowance has changed as ${option}`, 'success');
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      });
  }

  private whichVersionAllowence(snapshots: boolean, releases: boolean) {
    if (snapshots && !releases) {
      return RepoSupport.SNAPSHOTS;
    } else if (!snapshots && releases) {
      return RepoSupport.RELEASES;
    } else if (snapshots && releases) {
      return RepoSupport.ALL;
    }

    return null;
  }
}
