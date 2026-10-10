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
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';

/**
 * The extra sentence of the delete-version confirmation when the version is the artifact's last one and
 * the artifact is the only one of its group (RPS-1348): the server then removes the artifact and the
 * group with the version, and answers `DeletedItem.GROUP`.
 */
export function lastVersionOfGroupWarning(groupName: string, artifactName: string): string {
  return (
    `This is the only version of ${artifactName} and ${artifactName} is the only artifact of the group ${groupName}, ` +
    `so the artifact and the group are removed too. This cannot be undone.`
  );
}

/** The plain confirmation, or the one that names what else goes when the version is the last of its group. */
export function showVersionDeleteDialog(
  dangerModalService: DangerModalService,
  warning: string | null,
  call: () => void,
): void {
  if (warning) {
    dangerModalService.showWithMessage('Delete Version', 'Delete', warning, call);
  } else {
    dangerModalService.show('Delete Version', 'Delete', call);
  }
}
