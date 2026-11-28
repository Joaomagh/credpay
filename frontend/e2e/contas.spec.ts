import { expect, test, type Page } from '@playwright/test';

const senhaA = process.env['CREDPAY_CI_PASSWORD_A'];
const senhaB = process.env['CREDPAY_CI_PASSWORD_B'];

async function entrar(page: Page, usuario: string, senha: string, legado = false): Promise<void> {
  await expect(page.getByRole('heading', {name: 'Entre na sua conta'})).toBeVisible();
  await page.getByLabel('E-mail ou usuário', {exact: true}).fill(usuario);
  await page.getByLabel('Senha', {exact: true}).fill(senha);
  const login = page.waitForResponse(response => new URL(response.url()).pathname === '/auth/login');
  await page.getByRole('button', {name: 'Entrar', exact: true}).click();
  expect((await login).status()).toBe(204);
  await expect(page.getByRole('heading', {name: 'Clientes.', exact: true})).toBeVisible();
  if (legado) {
    await page.goto('/nova');
    await expect(page.getByRole('heading', {name: 'Nova transação.'})).toBeVisible();
  }
}

async function sair(page: Page): Promise<void> {
  await page.goto('/entrar');
  const logout = page.waitForResponse(response => new URL(response.url()).pathname === '/auth/logout');
  await page.getByRole('button', {name: 'Sair da conta'}).click();
  expect((await logout).status()).toBe(204);
  await expect(page.getByRole('heading', {name: 'Entre na sua conta'})).toBeVisible();
}

test('cadastro por e-mail cria conta vazia e recupera clientes após reentrada', async ({page}, testInfo) => {
  if (!senhaA) throw new Error('A senha descartável do ensaio não foi preparada.');
  const email = 'cadastro-' + testInfo.project.name + '-' + crypto.randomUUID() + '@example.com';
  const nomeCliente = 'Cliente do cadastro ' + testInfo.project.name;
  await test.step('cadastrar por e-mail sem Google', async () => {
    await page.goto('/entrar');
    await page.getByRole('link', {name: 'Criar conta com e-mail', exact: true}).click();
    await page.getByLabel('E-mail', {exact: true}).fill(email);
    await page.getByLabel('Senha', {exact: true}).fill(senhaA);
    await page.getByLabel('Confirme a senha', {exact: true}).fill(senhaA);
    const registration = page.waitForResponse(response => new URL(response.url()).pathname === '/auth/registro');
    await page.getByRole('button', {name: 'Criar minha conta', exact: true}).click();
    expect((await registration).status()).toBe(202);
    await expect(page.getByRole('heading', {name: 'Continue para entrar'})).toBeVisible();
    expect((await page.request.get('/auth/sessao')).status()).toBe(401);
  });
  await test.step('entrar na conta vazia e cadastrar um cliente', async () => {
    await page.getByRole('link', {name: 'Ir para entrar', exact: true}).click();
    await entrar(page, email, senhaA);
    await expect(page.getByRole('heading', {name: 'Nenhum cliente cadastrado.'})).toBeVisible();
    await page.getByLabel('Nome do cliente', {exact: true}).fill(nomeCliente);
    const created = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/clientes');
    await page.getByRole('button', {name: 'Cadastrar cliente', exact: true}).click();
    expect((await created).status()).toBe(201);
    await expect(page.getByRole('list', {name: 'Clientes da sua conta'})).toContainText(nomeCliente);
  });
  await test.step('sair e reentrar na mesma conta preserva o cliente', async () => {
    await sair(page);
    await entrar(page, email.toUpperCase(), senhaA);
    await expect(page.getByRole('list', {name: 'Clientes da sua conta'})).toContainText(nomeCliente);
    expect(await page.locator('body').evaluate(body => body.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});

test('duas contas isolam cadastro e consulta de clientes', async ({page}, testInfo) => {
  if (!senhaA || !senhaB) throw new Error('As contas descartáveis do ensaio não foram preparadas.');
  let posts = 0;
  let clienteId = '';
  const nomeCliente = 'Cliente Exemplo ' + testInfo.project.name;
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/clientes') posts++;
  });
  await test.step('cadastrar e consultar cliente com a conta A', async () => {
    await test.step('entrar na conta A para cadastrar cliente', async () => {
      await page.goto('/cadastros/clientes');
      await entrar(page, 'conta-ci-a', senhaA);
    });
    await test.step('abrir a página de clientes autenticada', async () => {
      await page.getByRole('link', {name: 'Clientes', exact: true}).click();
      await expect(page.getByRole('heading', {name: 'Clientes.'})).toBeVisible();
    });
    await test.step('enviar cliente e confirmar resposta de criação', async () => {
      await page.getByLabel('Nome do cliente', {exact: true}).fill(nomeCliente);
      const created = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/clientes');
      await page.getByRole('button', {name: 'Cadastrar cliente', exact: true}).click();
      const response = await created;
      expect(response.status()).toBe(201);
      clienteId = (await response.json() as {id: string}).id;
      await expect(page.getByText('Cliente cadastrado', {exact: true})).toBeVisible();
    });
    const list = page.getByRole('list', {name: 'Clientes da sua conta'});
    await test.step('confirmar cliente na lista da conta', async () => {
      await expect(list).toContainText(nomeCliente);
    });
    await test.step('consultar cliente por identificador', async () => {
      const query = page.waitForResponse(response => new URL(response.url()).pathname === '/clientes/' + clienteId);
      await list.getByRole('button', {name: 'Ver cadastro de ' + nomeCliente, exact: true}).click();
      expect((await query).status()).toBe(200);
      await expect(page.getByRole('heading', {name: 'Cadastro do cliente'})).toBeVisible();
    });
  });
  await test.step('recarregar preserva o cadastro sem outro POST', async () => {
    await page.reload();
    await expect(page.getByRole('list', {name: 'Clientes da sua conta'})).toContainText(nomeCliente);
    expect(posts).toBe(1);
  });
  await test.step('a conta B não encontra o cliente de A', async () => {
    await sair(page);
    await entrar(page, 'conta-ci-b', senhaB);
    await page.getByRole('link', {name: 'Clientes', exact: true}).click();
    await expect(page.getByRole('heading', {name: 'Nenhum cliente cadastrado.'})).toBeVisible();
    await expect(page.getByText(nomeCliente, {exact: true})).toHaveCount(0);
    expect((await page.request.get('/clientes/' + clienteId)).status()).toBe(404);
  });
});

test('duas contas isolam sessão, consulta e recibo na mesma aba', async ({page, context}) => {
  if (!senhaA || !senhaB) throw new Error('As contas descartáveis do ensaio não foram preparadas.');
  let posts = 0;
  let id = '';
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/transacoes') posts++;
  });

  await test.step('autenticar A, criar e acompanhar a aprovação real', async () => {
    await page.goto('/nova');
    await entrar(page, 'conta-ci-a', senhaA, true);
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
    await entrar(page, 'conta-ci-b', senhaB, true);
    await expect(page.getByText('Intenção recuperada desta aba', {exact: true})).toHaveCount(0);
    await expect(page.getByRole('region', {name: 'Resultado da transação'})).toHaveCount(0);
    const denied = page.waitForResponse(response => new URL(response.url()).pathname === '/transacoes/' + id);
    await page.goto('/consultar?id=' + id);
    expect((await denied).status()).toBe(404);
    await expect(page.getByRole('alert')).toContainText('Transação não encontrada');
  });

  await test.step('retomar o recibo de A sem outro POST', async () => {
    await sair(page);
    await entrar(page, 'conta-ci-a', senhaA, true);
    await expect(page.getByText('Intenção recuperada desta aba', {exact: true})).toBeVisible();
    await expect(page.locator('code')).toHaveText(id);
    expect(posts).toBe(1);
  });
});
