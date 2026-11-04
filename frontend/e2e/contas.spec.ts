import { expect, test, type Page } from '@playwright/test';

const senhaA = process.env['CREDPAY_CI_PASSWORD_A'];
const senhaB = process.env['CREDPAY_CI_PASSWORD_B'];

async function entrar(page: Page, usuario: string, senha: string): Promise<void> {
  await expect(page.getByRole('heading', {name: 'Entre na sua conta'})).toBeVisible();
  await page.getByLabel('Usuário', {exact: true}).fill(usuario);
  await page.getByLabel('Senha', {exact: true}).fill(senha);
  const login = page.waitForResponse(response => new URL(response.url()).pathname === '/auth/login');
  await page.getByRole('button', {name: 'Entrar', exact: true}).click();
  expect((await login).status()).toBe(204);
  await expect(page.getByRole('heading', {name: 'Sessão ativa'})).toBeVisible();
  await page.getByRole('link', {name: 'Abrir console'}).click();
  await expect(page.getByRole('heading', {name: 'Nova transação.'})).toBeVisible();
}

async function sair(page: Page): Promise<void> {
  await page.goto('/entrar');
  await page.getByRole('button', {name: 'Sair da conta'}).click();
  await expect(page.getByText('Sessão encerrada.', {exact: true})).toBeVisible();
}

test('duas contas isolam sessão, consulta e recibo na mesma aba', async ({page, context}) => {
  if (!senhaA || !senhaB) throw new Error('As contas descartáveis do ensaio não foram preparadas.');
  let posts = 0;
  let id = '';
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/transacoes') posts++;
  });

  await test.step('autenticar A, criar e acompanhar a aprovação real', async () => {
    await page.goto('/nova');
    await entrar(page, 'conta-ci-a', senhaA);
    await page.getByRole('textbox', {name: 'Valor da transação'}).fill('50,001');
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/transacoes');
    await page.getByRole('button', {name: 'Criar transação', exact: true}).click();
    expect((await created).status()).toBe(201);
    await expect(page.getByRole('link', {name: 'Acompanhar transação'})).toBeVisible();
    id = await page.locator('code').innerText();
    await page.getByRole('link', {name: 'Acompanhar transação'}).click();
    await expect(page.getByText('APROVADA', {exact: true})).toBeVisible();
    await expect(page.locator('code')).toHaveText(id);
  });

  await test.step('encerrar A em outra aba e confirmar a saída na primeira', async () => {
    const outra = await context.newPage();
    try {
      await sair(outra);
      await expect(page).toHaveURL(/\/entrar$/);
      await expect(page.getByRole('region', {name: 'Resultado da transação'})).toHaveCount(0);
    } finally {
      await outra.close();
    }
  });

  await test.step('autenticar B e recusar o UUID e o recibo de A', async () => {
    await entrar(page, 'conta-ci-b', senhaB);
    await expect(page.getByText('Intenção recuperada desta aba', {exact: true})).toHaveCount(0);
    await expect(page.getByRole('region', {name: 'Resultado da transação'})).toHaveCount(0);
    const denied = page.waitForResponse(response => new URL(response.url()).pathname === '/transacoes/' + id);
    await page.goto('/consultar?id=' + id);
    expect((await denied).status()).toBe(404);
    await expect(page.getByRole('alert')).toContainText('Transação não encontrada');
  });

  await test.step('retomar o recibo de A sem outro POST', async () => {
    await sair(page);
    await entrar(page, 'conta-ci-a', senhaA);
    await expect(page.getByText('Intenção recuperada desta aba', {exact: true})).toBeVisible();
    await expect(page.locator('code')).toHaveText(id);
    expect(posts).toBe(1);
  });
});
