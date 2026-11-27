import { normalizeClientName } from './client';

describe('Nome de cliente', () => {
  it.each(['', ' ', '\u00a0\u2003\u202f', 'Cliente\nExemplo', 'Cliente\u007f', 'Cliente\u0085', '\ud800', '\udc00'])(
    'recusa nome vazio, controles ou Unicode incompleto (%j)', value => {
      expect(() => normalizeClientName(value)).toThrow('Informe um nome de 1 a 120 caracteres, sem controles.');
    });

  it('remove espaços externos Unicode e preserva acentos e pontuação', () => {
    expect(normalizeClientName('\u00a0 João d’Ávila-Silva \u2003')).toBe('João d’Ávila-Silva');
  });

  it('conta pontos de código em vez de unidades UTF-16', () => {
    const caractere = '\ud801\udc00';
    expect(normalizeClientName(caractere.repeat(120))).toBe(caractere.repeat(120));
    expect(() => normalizeClientName(caractere.repeat(121))).toThrow();
  });
});
