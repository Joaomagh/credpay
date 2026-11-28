import { Component, input } from '@angular/core';

@Component({
  selector: 'cp-icon',
  template: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="paths[name()] || paths['arrow']" /></svg>`,
  styles: [':host{display:inline-flex;width:1.2em;height:1.2em;flex-shrink:0;vertical-align:middle}svg{width:100%;height:100%}']
})
export class Icon {
  readonly name = input('arrow');
  readonly paths: Record<string, string> = {
    users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.87M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5 19 19M5 19l1.5-1.5M17.5 6.5 19 5',
    moon: 'M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13',
    logout: 'M9 21H4V3h5M14 8l5 4-5 4M8 12h11',
    plus: 'M12 5v14M5 12h14', search: 'M21 21l-5-5M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15',
    arrow: 'M4 12h16m-6-6 6 6-6 6', check: 'm5 12 4 4L19 6', clock: 'M12 8v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
    cross: 'm6 6 12 12M6 18 18 6', copy: 'M9 9h11v12H9zM15 5V2H3v13h3',
    shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6zM8 12l3 3 5-6',
    bolt: 'm13 2-9 12h7l-1 8 10-13h-7z', bank: 'm3 8 9-5 9 5H3m2 3v7m5-7v7m4-7v7m5-7v7M3 21h18',
    refresh: 'M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-2l2 3M4 16l2 3a8 8 0 0 0 13-2'
  };
}
