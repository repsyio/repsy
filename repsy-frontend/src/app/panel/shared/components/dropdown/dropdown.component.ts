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

import { Component, ElementRef, HostListener } from '@angular/core';

@Component({
  selector: 'app-dropdown',
  templateUrl: './dropdown.component.html',
  standalone: true,
  imports: [],
})
export class DropdownComponent {
  public isOpen = false;
  private static activeDropdown: DropdownComponent | null = null;

  constructor(private eRef: ElementRef) {}

  toggleDropdown() {
    if (DropdownComponent.activeDropdown && DropdownComponent.activeDropdown !== this) {
      DropdownComponent.activeDropdown.isOpen = false;
    }

    this.isOpen = !this.isOpen;
    DropdownComponent.activeDropdown = this.isOpen ? this : null;
  }

  @HostListener('document:click', ['$event'])
  clickOutside(event: Event) {
    if (!this.eRef.nativeElement.contains(event.target)) {
      this.isOpen = false;
      DropdownComponent.activeDropdown = null;
    }
  }
}
