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

import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

import { ScanStatus } from '../../../../../generated/api';
import { hasRescanFailed, recentScanNote } from '../../util/rescan-status.util';

/** Flags a recent scan whose newest scan is pending, running or failed. Renders nothing otherwise. */
@Component({
  selector: 'app-rescan-note',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './rescan-note.component.html',
})
export class RescanNoteComponent {
  @Input() public status: ScanStatus | null | undefined = null;
  /** Whether an earlier scan of the version completed, i.e. whether the severity beside it is real. */
  @Input() public hasCompletedScan = false;

  public get note(): string {
    return recentScanNote(this.status, this.hasCompletedScan);
  }

  public get failed(): boolean {
    return hasRescanFailed(this.status);
  }
}
