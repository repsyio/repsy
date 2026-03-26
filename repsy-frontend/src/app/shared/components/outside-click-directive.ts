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

import { Directive, ElementRef, EventEmitter, Input, OnDestroy, OnInit, Output, Renderer2 } from '@angular/core';

@Directive({
  selector: '[appOutSideClick]',
  standalone: true,
})
export class OutSideClickDirective implements OnInit, OnDestroy {
  @Input() appOutSideClick!: boolean;
  @Output() outSideClick = new EventEmitter<void>();
  constructor(
    private element: ElementRef,
    private renderer: Renderer2,
  ) {}

  private listener: (() => void) | undefined;

  // Execute this function when click outside of the dropdown-container
  onDocumentClick = (event: Event) => {
    if (!this.element.nativeElement.parentElement.contains(event.target)) {
      console.log(this.element.nativeElement.parentElement, event.target);
      this.outSideClick.emit();
    }
  };

  //Add the listener when the dropdown component is rendered
  ngOnInit(): void {
    this.listener = this.renderer.listen('document', 'click', this.onDocumentClick);
  }

  //To reduce unnecessary memory leaks you need to use the clean-up
  ngOnDestroy(): void {
    if (this.listener) {
      this.listener();
    }
  }
}
