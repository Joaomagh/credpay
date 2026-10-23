import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

test('aprovação com decimal exato e recibo retomado após reload', async ({page}) => {
  let posts = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/transacoes') posts++;
  });
  let id = '';
  await test.step('criar pela interface e receber contrato HTTP real', async () => {
    await page.goto('/nova');
    await page.getByRole('textbox', {name: 'Valor da transação'}).fill('50,001');
    const received = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/transacoes');
    await page.getByRole('button', {name: 'Criar transação', exact: true}).click();
    const response = await received;
    expect(response.status()).toBe(201);
    expect(response.request().postData()).toMatch(/"valor"\s*:\s*50\.001\s*[,}]/);
    expect(response.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/i);
    await expect(page.getByRole('link', {name: 'Acompanhar transação'})).toBeVisible();
    id = await page.locator('code').innerText();
    expect(response.headers()['location']).toBe('/transacoes/' + id);
    await expect(page.getByText('PENDENTE', {exact: true})).toBeVisible();
    await expect(page.getByRole('region', {name: 'Resultado da transação'})).toContainText('50,001');
  });
  await test.step('retomar recibo sem repetir criação', async () => {
    await page.reload();
    await expect(page.getByText('Intenção recuperada desta aba', {exact: true})).toBeVisible();
    await expect(page.locator('code')).toHaveText(id);
    await expect(page.getByRole('button', {name: 'Criar transação', exact: true})).toBeDisabled();
    await page.getByRole('link', {name: 'Acompanhar transação'}).click();
    await expect(page.getByText('APROVADA', {exact: true})).toBeVisible();
    await expect(page.locator('code')).toHaveText(id);
    expect(posts).toBe(1);
    expect(await page.locator('body').evaluate(body => body.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  });
});

test('rejeição vem do processador real', async ({page}) => {
  await test.step('criar valor acima do limite e acompanhar', async () => {
    await page.goto('/nova');
    await page.getByRole('textbox', {name: 'Valor da transação'}).fill('150,001');
    await page.getByRole('button', {name: 'Criar transação', exact: true}).click();
    await page.getByRole('link', {name: 'Acompanhar transação'}).click();
    await expect(page.getByText('REJEITADA', {exact: true})).toBeVisible();
    await expect(page.getByRole('region', {name: 'Resultado da transação'})).toContainText('150,001');
  });
});

test('validação local e transação inexistente têm mensagens próprias', async ({page}) => {
  await test.step('rejeitar UUID inválido sem HTTP', async () => {
    let gets = 0;
    page.on('request', r => { if (r.method() === 'GET' && new URL(r.url()).pathname.startsWith('/transacoes/')) gets++; });
    await page.goto('/consultar');
    await page.getByRole('textbox', {name: 'Identificador da transação · UUID'}).fill('invalido');
    await page.getByRole('button', {name: 'Consultar transação', exact: true}).click();
    await expect(page.getByRole('alert')).toContainText('Informe um UUID válido');
    expect(gets).toBe(0);
  });
  await test.step('exibir ausência informada pelo backend real', async () => {
    await page.getByRole('textbox', {name: 'Identificador da transação · UUID'}).fill(randomUUID());
    const received = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname.startsWith('/transacoes/'));
    await page.getByRole('button', {name: 'Consultar transação', exact: true}).click();
    expect((await received).status()).toBe(404);
    await expect(page.getByRole('alert')).toContainText('Transação não encontrada');
    await expect(page.getByRole('region', {name: 'Resultado da transação'})).toHaveCount(0);
  });
});
