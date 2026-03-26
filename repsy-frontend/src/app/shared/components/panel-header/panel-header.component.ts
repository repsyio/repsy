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

import { NgOptimizedImage } from '@angular/common';
import { Component, EventEmitter, HostListener, Output } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../../auth/pages/service/auth.service';
import { ProfileAvatarComponent } from '../../../panel/shared/components/avatar/profile.avatar.component';
import { DividerComponent } from '../divider/divider.component';

@Component({
  selector: 'app-panel-header',
  templateUrl: './panel-header.component.html',
  styleUrls: ['./panel-header.component.css'],
  imports: [RouterLink, DividerComponent, NgOptimizedImage, ProfileAvatarComponent],
  standalone: true,
})
export class PanelHeaderComponent {
  public username: string;
  public email: string;
  public isMobileMenuOpen = false;

  @Output() mobileMenuToggle = new EventEmitter<boolean>();

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router,
  ) {
    this.username = this.authService.username;
    this.email = this.authService.email;
  }

  docDropdown = false;
  profileDropdown = false;

  openMobileMenu() {
    this.isMobileMenuOpen = true;
    this.mobileMenuToggle.emit(this.isMobileMenuOpen);
  }

  toggleDocDropdown(event: Event) {
    event.stopPropagation();
    this.docDropdown = !this.docDropdown;
    this.profileDropdown = false;
  }

  toggleProfileDropdown(event: Event) {
    event.stopPropagation();
    this.profileDropdown = !this.profileDropdown;
    this.docDropdown = false;
  }

  public logOut(): void {
    this.authService.logOut();
    this.router.navigateByUrl('login');
  }

  @HostListener('document:click')
  onDocumentClick() {
    this.docDropdown = false;
    this.profileDropdown = false;
  }

  @HostListener('window:scroll')
  onWindowScroll() {
    if (window.pageYOffset > 100) {
      document.querySelector('header')?.classList.add('smaller');
    } else {
      document.querySelector('header')?.classList.remove('smaller');
    }
  }
}
