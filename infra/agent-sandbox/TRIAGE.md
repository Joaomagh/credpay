# Triagem dos advisories do core diagnóstico

Corte:17/10/2026. Esta matriz analisa os componentes parciais do sandbox, sem reavaliar os aplicativos Java. Não é aceite de risco, VEX aprovado, remediação ou autorização de uso do agente no core.

## Evidência e decisão

O Sandbox Core20/run39069690372, headf4c329153757414bf797d4a31889862c82ca1748, reconheceu Debian13 e28 componentes com nome/versão/arquitetura/fontes exatos. Artefato12113644935, SHA256f12adb581ae88b000cd4aef5b2079e3ea168f89357a80c0ada6cb77d60a0d0e9, checkout de testee343601f98962953b1ad9b1ceea664fc5796abc4, imagemsha256:c6c15b5f89660a2db32468b2a6aea9dae44ce90da8de570631d798c936ed8770. Vínculos/SBOM recomposto conferidos; comandos/metadados DB/limites em spec§9.104.

Resultado:LOW24/MEDIUM21/HIGH17/CRITICAL2. São19 ocorrências HIGH/CRITICAL em11 CVEs distintos/sete binários; os quatro IDs util-linux aparecem emlibblkid1/libmount1/mount. Nenhum desses19 findings trouxe FixedVersion no relatório. Severidade é a do scanner; notas de prioridade do Debian não a substituem. Todos os achados continuam no inventário, inclusive sem correção.

**Baseline permanece sem aceite.** O tracker consultado não apresenta pacote trixie corrigido para esses11IDs. Existem patches/versões em outras linhas para parte deles, que não foram instalados. Não misturar pacotes sid em Debian13, compilar patches ou trocar distribuição automaticamente. Warning de SBOM terceiro e escopo parcial permanecem explícitos; não se inventa dpkg íntegro após a poda.

## Matriz de aplicabilidade e ação

Os links dos IDs são o tracker oficial Debian. “Ausente” significa ausente do manifesto/export auditados; não é declaração automática de código vulnerável ausente ou de não exploração.

| CVE / fonte | Evidência no core e pré-condições a revisar | Correção / próxima ação |
|---|---|---|
| [2026-40467](https://security-tracker.debian.org/tracker/CVE-2026-40467), gawk, HIGH | `io.c:do_getline_redir()`; `usr/bin/awk` contém o ELF de gawk5.2.1. Nenhum privilégio é requisito informado. | Trixie/sid ainda vulneráveis. Tracker refere commit upstreama2d18c7; conteúdo do patch não foi revisto. Priorizar correção real antes de promover o core. |
| [2026-40468](https://security-tracker.debian.org/tracker/CVE-2026-40468), gawk, CRITICAL | Overflow em builtin.c/memória; executável gawk presente. UID não-root não remove corrupção dentro do processo. | Trixie/sid sem pacote corrigido; tracker refere patch062f2f2581b991362c046f7f2e238ffa34e6f8c7. Não aplicado/revisado neste incremento. |
| [2026-40469](https://security-tracker.debian.org/tracker/CVE-2026-40469), gawk, CRITICAL | Descrição menciona builds32bit; projeção usa linuxamd64/ELF64. A diferença de arquitetura exige análise do código/ABI. | Trixie/sid sem pacote corrigido; patch upstream referidoae1b2d508f46913269a9e62aceda3636afe8147b. Manter finding; não declarar não afetado só pelo nome amd64. |
| [2026-40553](https://security-tracker.debian.org/tracker/CVE-2026-40553), gawk, HIGH | `extension/readdir.c:ftype()`; `readdir.so` não está selecionado. | Sem pacote corrigido trixie/sid; tracker refere commitcca0366. Candidata a análise de separação do código da extensão; confirmar objetos/vinculação antes de concluir. |
| [2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369), acl, HIGH | libacl está presente; APIs pathname seguem symlinks. Escalada descrita depende de chamador privilegiado/caminho controlável. Consumidores concretos ainda precisam ser mapeados. | Trixie2.3.2 vulnerável; acl2.4.0-1 emsid/forky não basta sozinho: chamadores precisam usar APIs novas. Avaliar biblioteca e consumidores em conjunto. |
| [2026-76642](https://security-tracker.debian.org/tracker/CVE-2026-76642), util-linux, HIGH | mount/libmount presentes; cadeia descrita envolve mount SUID/root, fstab autorizado, helper e hooks após falha. Core tem mount0555, UID10001/capszero/NNP e não seleciona fstab/helper. | Trixie2.41.5 sem correção; tracker unstable2.42.3-1, upstream2.41.6/2.42.3. Controles limitam a cadeia descrita; não removem código/finding. |
| [2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408), util-linux, HIGH | `nsenter --join-cgroup`, descritor aberto com root/CAP_SYS_ADMIN e herdado após troca de credenciais; nsenter ausente. | Trixie sem correção. Tracker2.42.4-1 diverge do advisory2.41.6/2.42.3; resolver divergência antes de escolher versão. Investigar separação do código nsenter. |
| [2026-78409](https://security-tracker.debian.org/tracker/CVE-2026-78409), util-linux, HIGH | libmount presente; advisory aponta X-mount.subdir/open_tree, com presença do caminho na compilação exata ainda a confirmar; kernel≥6.15 é requisito e CI6.17 o satisfaz. Revisar privilégio/fstab e compilação exata. | Trixie sem correção; unstable2.42.3-1, upstream2.41.6/2.42.3. Não alegar proteção por versão do kernel. |
| [2026-78410](https://security-tracker.debian.org/tracker/CVE-2026-78410), util-linux, HIGH | Bind autorizado por fstab resolve novamente fonte alterável; hooks owner/group/mode dependem de operações privilegiadas. mount/libmount presentes; essas pré-condições não foram demonstradas no worker restrito. | Trixie sem correção; unstable2.42.3-1, upstream2.41.6/2.42.3. Confirmar caminho e correção coerente da fonte/binários. |
| [2026-16742](https://security-tracker.debian.org/tracker/CVE-2026-16742), systemd, HIGH | systemd-homed/home_authenticating_finish exige usuário gerenciado/logado e adoção de registro sem assinatura. Core seleciona libsystemd; homed/homework ausentes. | Trixie257.13 sem correção; unstable261.2-1; upstream258.10/259.8/260.4/261.2/262. Candidata a análise de separação dos objetos do daemon; não declarar não afetado ainda. |
| [2025-69720](https://security-tracker.debian.org/tracker/CVE-2025-69720), ncurses, HIGH | `progs/infocmp.c:analyze_string()`; infocmp ausente, libtinfo presente. | Trixie6.5+20250216-2 vulnerável; tracker registra correção emunstable6.6+20251231-1. Confirmar separação entre utilitário e biblioteca selecionada. |

Detalhes das pré-condições e versões: advisories dos mantenedores util-linux [76642](https://github.com/util-linux/util-linux/security/advisories/GHSA-m25x-3hj9-m26f), [78408](https://github.com/util-linux/util-linux/security/advisories/GHSA-55fx-f4gg-cfhj), [78409](https://github.com/util-linux/util-linux/security/advisories/GHSA-8f2p-47x3-43mv), [78410](https://github.com/util-linux/util-linux/security/advisories/GHSA-rh77-686x-2f2m), [systemd](https://github.com/systemd/systemd/security/advisories/GHSA-jm29-p7hh-vjhv) e [divulgação ACL pelo mantenedor](https://www.openwall.com/lists/oss-security/2026/06/29/1). ACL mantém as APIs antigas por compatibilidade; a atualização isolada da biblioteca não prova adoção das alternativas `acl_*_at` pelos consumidores.

## Próximos incrementos

1. Preservar inventário e não promover essa imagem para operação do agente. Dados dos testes CI são fictícios, com worker restrito; isso não autoriza ambiente externo ou waiver.
2. Priorizar gawk40467/40468 e uma rota de correção verificável. Qualquer nova baseline exige origem/checksum/inventário e repetição dos controles afetados; nenhum patch referido foi executado.
3. Demonstrar separação de código para40553/78408/16742/69720 e revisar a condição32bit de40469. Registrar conclusão técnica e lacunas sem alterar o resultado bruto do scanner.
4. Mapear consumidores ACL e resolver divergência de versões util-linux. Reduzir versão/apagar findings para fazer o CI passar é vedado.
5. Continuar os negativos independentes de CPU/memória/PID/rede/daemon/ferramentas como experimentos CI próprios, sem apresentar isso como remediação dos CVEs ou aceite do core.

Sem mudança de dependência, supressão, exploit, pagamento real, acesso a arquivos pessoais ou execução Docker local nesta triagem.
