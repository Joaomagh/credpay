import { DOCUMENT, Injectable, inject, signal } from '@angular/core';

@Injectable({providedIn: 'root'})
export class Theme {
  private readonly document = inject(DOCUMENT);
  readonly mode = signal<'light' | 'dark'>('dark');
  constructor() {
    try {
      if (this.document.defaultView?.localStorage.getItem('credpay.theme') === 'light') this.mode.set('light');
    } catch { /* Sem armazenamento, a aparência continua funcionando nesta página. */ }
    this.apply();
  }
  toggle(): void {
    this.mode.update(mode => mode === 'dark' ? 'light' : 'dark');
    this.apply();
    try { this.document.defaultView?.localStorage.setItem('credpay.theme', this.mode()); }
    catch { /* A escolha permanece nesta página quando armazenamento está indisponível. */ }
  }
  private apply(): void { this.document.documentElement.dataset['theme'] = this.mode(); }
}
