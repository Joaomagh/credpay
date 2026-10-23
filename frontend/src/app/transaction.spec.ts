import { amount, displayAmount } from './transaction';

describe('Valor decimal do contrato', () => {
  it('normaliza vírgula sem perder dígitos nem arredondar centavos adicionais', () => {
    expect(amount(' 9007199254740993,0123 ')).toBe('9007199254740993.0123');
    expect(amount('000250.00')).toBe('250.00');
    expect(amount('0,001')).toBe('0.001');
  });
  it('rejeita zero, negativos e entradas ambíguas', () => {
    for (const input of ['', '0', '0,000', '-1', 'NaN', 'Infinity', '1.000,50', '1e2', '1,2,3']) {
      expect(amount(input), input).toBeNull();
    }
  });
  it('formata o retorno decimal ou exponencial sem usar Number para dinheiro', () => {
    expect(displayAmount('9007199254740993.0123')).toBe('9.007.199.254.740.993,0123');
    expect(displayAmount('2.5E+2')).toBe('250,00');
    expect(displayAmount('1E-3')).toBe('0,001');
  });
});
