import { validEmail } from './email';

describe('Formato do e-mail de acesso', () => {
  it.each(['pessoa@example.com', 'Pessoa.Exemplo+CredPay@EXAMPLE.COM', "pessoa_o'connor@example.com", 'a@sub.example.com'])
    ('aceita endereço usual %s', email => expect(validEmail(email)).toBe(true));

  it.each(['', 'sem-arroba', 'a@@example.com', 'a@localhost', '.a@example.com', 'a.@example.com', 'a..b@example.com',
    'a@-example.com', 'a@example-.com', 'a@example..com', 'a@127.0.0.1', 'á@example.com', 'a b@example.com', 'a@example.com:80'])
    ('recusa endereço inválido %s antes de enviar', email => expect(validEmail(email)).toBe(false));

  it('limita endereço, parte local e rótulo do domínio', () => {
    expect(validEmail('a'.repeat(65) + '@example.com')).toBe(false);
    expect(validEmail('a@' + 'a'.repeat(64) + '.com')).toBe(false);
    expect(validEmail('a'.repeat(100000) + '@example.com')).toBe(false);
  });
});
