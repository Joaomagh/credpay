import { Component, input } from '@angular/core';

@Component({
  selector: 'cp-icon',
  template: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="paths[name()] || paths['arrow']" /></svg>`,
  styles: [':host{display:inline-flex;width:1.2em;height:1.2em;flex-shrink:0;vertical-align:middle}svg{width:100%;height:100%}']
})
export class Icon {
  readonly name = input('arrow');
  readonly paths: Record<string, string> = {
    plus: 'M12 5v14M5 12h14', search: 'M21 21l-5-5M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15',
    arrow: 'M4 12h16m-6-6 6 6-6 6', check: 'm5 12 4 4L19 6', clock: 'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    cross: 'm6 6 12 12M6 18 18 6', copy: 'M9 9h11v12H9zM15 5V2H3v13h3',
    shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6zM8 12l3 3 5-6',
    bolt: 'm13 2-9 12h7l-1 8 10-13h-7z', bank: 'm3 8 9-5 9 5H3m2 3v7m5-7v7m4-7v7m5-7v7M3 21h18',
    refresh: 'M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-2l2 3M4 16l2 3a8 8 0 0 0 13-2'
  };
}
