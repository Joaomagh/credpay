import { TestBed } from '@angular/core/testing';
import { Theme } from './theme';

describe('Preferência de aparência', () => {
  afterEach(() => { localStorage.removeItem('credpay.theme'); document.documentElement.removeAttribute('data-theme'); });

  it('inicia escuro, alterna para claro e preserva a escolha ao reabrir', () => {
    localStorage.removeItem('credpay.theme');
    const theme = TestBed.inject(Theme);
    expect(theme.mode()).toBe('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
    theme.toggle();
    expect(theme.mode()).toBe('light');
    expect(document.documentElement.dataset['theme']).toBe('light');
    TestBed.resetTestingModule();
    expect(TestBed.inject(Theme).mode()).toBe('light');
  });

  it('descarta preferência inválida e permite alternar mesmo sem armazenamento', () => {
    localStorage.setItem('credpay.theme', 'valor-invalido');
    const theme = TestBed.inject(Theme);
    expect(theme.mode()).toBe('dark');
    const failure = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('indisponível'); });
    try {
      expect(() => theme.toggle()).not.toThrow();
      expect(document.documentElement.dataset['theme']).toBe('light');
    } finally { failure.mockRestore(); }
  });
});
