import { spawnSync } from 'node:child_process';
import { pbkdf2Sync, randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const env = process.env;
const project = env.COMPOSE_PROJECT_NAME;
const expected = `credpay-ci-${env.GITHUB_RUN_ID}-${env.GITHUB_RUN_ATTEMPT}`;
if (env.GITHUB_ACTIONS !== 'true' || env.CI !== 'true'
    || !/^credpay-ci-\d+-\d+$/.test(project ?? '') || project !== expected) {
  throw new Error('O ensaio de contas exige o projeto descartável desta execução de CI.');
}

const root = fileURLToPath(new URL('../../', import.meta.url));
const frontend = fileURLToPath(new URL('../', import.meta.url));
const compose = ['compose', '--project-name', project, '--env-file', 'infra/compose/.env.example',
  '-f', 'infra/compose/compose.yaml', '-f', 'infra/compose/compose.contas-ci.yaml'];

function execute(stage, command, args, options) {
  const result = spawnSync(command, args, options);
  if (result.status !== 0) throw new Error(`Ensaio de contas interrompido na etapa ${stage}.`);
}

function fixture(usuario) {
  const senha = randomBytes(24).toString('base64url');
  const salt = randomBytes(16);
  const hash = Buffer.concat([salt, pbkdf2Sync(senha, salt, 600000, 32, 'sha256')]).toString('hex');
  return {senha, row: `('${randomUUID()}', '${usuario}', '${hash}')`};
}

const docker = {cwd: root, env: {...env, CREDPAY_TOPOLOGY_ENABLED: 'true', CREDPAY_FLOW_ENABLED: 'true'},
  stdio: ['pipe', 'ignore', 'ignore'], timeout: 120000};
const a = fixture('conta-ci-a');
const b = fixture('conta-ci-b');
execute('contas fictícias', 'docker', [...compose, 'exec', '-T', 'db-transacoes', 'sh', '-ec',
  'psql --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
  ...docker, input: `INSERT INTO contas (id, usuario, senha_hash) VALUES ${a.row}, ${b.row};\n`
});
execute('perfil de sessão', 'docker', [...compose, 'up', '--detach', '--no-deps', '--no-build', '--wait',
  '--wait-timeout', '90', 'transacoes'], docker);

execute('navegador', process.execPath, ['node_modules/playwright/cli.js', 'test'], {
  cwd: frontend, stdio: ['ignore', 'inherit', 'ignore'], timeout: 200000,
  env: {...env, CREDPAY_BROWSER_ACCOUNTS: 'true', CREDPAY_CI_PASSWORD_A: a.senha, CREDPAY_CI_PASSWORD_B: b.senha}
});
