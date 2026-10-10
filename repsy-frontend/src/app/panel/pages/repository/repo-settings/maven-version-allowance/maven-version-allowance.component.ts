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

import { ReposApi, RepoSettingsForm } from '../../../../../../generated/api';
import { SelectorComponent } from '../../../../shared/components/selector/selector.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoSupport, RepoType } from '../../../../shared/dto/repo/repo-type';
import { saveRepoSetting } from '../save-repo-setting';

@Component({
  selector: 'app-maven-version-allowance',
  templateUrl: './maven-version-allowance.component.html',
  styleUrls: ['./maven-version-allowance.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, SelectorComponent, RouterLink],
})
export class MavenVersionAllowanceComponent implements OnInit {
  @Input() public parentForm: FormGroup;
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Output() public fetch = new EventEmitter<void>();

  public selectedOption: RepoSupport = RepoSupport.ALL;
  public repoOptions: RepoSupport[] = [];

  /** A save is on its way: the selector is locked, so a double click sends one request (RPS-1618). */
  public saving = false;

  /** The option the repository has, to go back to when a save fails. */
  private savedOption: RepoSupport = RepoSupport.ALL;

  private readonly MAVEN_OPTIONS = [RepoSupport.ALL, RepoSupport.SNAPSHOTS, RepoSupport.RELEASES];
  private readonly NUGET_OPTIONS = [RepoSupport.ALL, RepoSupport.PRE_RELEASE, RepoSupport.STABLE];

  constructor(
    private readonly reposApi: ReposApi,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.repoOptions = this.repoType === RepoType.NUGET ? this.NUGET_OPTIONS : this.MAVEN_OPTIONS;

    const snapshots = this.parentForm.get('snapshots')!.value;
    const releases = this.parentForm.get('releases')!.value;
    this.selectedOption = this.resolveSelectedOption(snapshots, releases);
    this.savedOption = this.selectedOption;
  }

  public selectType(option: string) {
    const snapshots =
      option === RepoSupport.SNAPSHOTS || option === RepoSupport.PRE_RELEASE || option === RepoSupport.ALL;
    const releases = option === RepoSupport.RELEASES || option === RepoSupport.STABLE || option === RepoSupport.ALL;

    this.saving = true;

    // Only the two fields this selector owns are sent (RPS-1619): the rest of the form was loaded when the page opened.
    const form: RepoSettingsForm = { snapshots, releases };

    saveRepoSetting(this.reposApi.updateRepoSettings(this.repoName, form), {
      saved: () => {
        this.savedOption = option as RepoSupport;
        this.fetch.emit();
        this.toastService.show(`Version allowance has changed to ${option}`, 'success');
      },
      failed: () => (this.selectedOption = this.savedOption),
      settled: () => (this.saving = false),
    });
  }

  private resolveSelectedOption(snapshots: boolean, releases: boolean): RepoSupport {
    if (this.repoType === RepoType.NUGET) {
      if (snapshots && !releases) {
        return RepoSupport.PRE_RELEASE;
      }
      if (!snapshots && releases) {
        return RepoSupport.STABLE;
      }
      return RepoSupport.ALL;
    }

    if (snapshots && !releases) {
      return RepoSupport.SNAPSHOTS;
    }
    if (!snapshots && releases) {
      return RepoSupport.RELEASES;
    }
    return RepoSupport.ALL;
  }
}
