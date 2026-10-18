# Conciliação dos advisories da candidata Noble

Corte18/10/2026. A PR174 avaliou uma candidata, sem substituir o padrão Debian e sem aceite automático. Resultado auditado:31componentes,95arquivos,65 testes Linux sem skips e controles atuais aprovados. Evidência detalhada em spec9.117; artefato12123579802/SHA25693e3df579ef38a10b46f1a8af649f27bfe4c8cf131ff81fe7ade39b2e09029ec.

## O que o scanner permite concluir

Trivy0.75.0 reconheceu Ubuntu24.04 e a cobertura binário/fonte exata dos31componentes:HIGH0/CRITICAL0/MEDIUM11/LOW1. Nenhum dos11IDs encontrados na baseline Debian foi emitido. Esse resultado descreve o scan parcial com sua base e seus filtros; não demonstra que todos os códigos vulneráveis foram corrigidos ou que toda a imagem/agente está segura.

## Conciliação dos11IDs anteriores

Versões são as selecionadas na projeção Noble, não inferidas pelo nome da imagem. As versões fixed vêm dos [registros oficiais Canonical](SANDBOX_REMEDIATION_PROPOSAL.md). Não usar ausência de finding como disposição técnica.

| IDs | Componente candidato | Evidência / conclusão permitida | Lacuna antes do aceite |
|---|---|---|---|
| gawk40467/40468/40469 | gawk1:5.2.1-2ubuntu0.1 | Backport oficialUbuntu publicado; DEB fixado porSHA/control, ELF projetado ligado a esse pacote; versão instalada corresponde àfixed oficial | Não representa correção de todas as vulnerabilidades do core; PROCINFO5.2.1 sozinho não prova patch |
| gawk40553 | gawk1:5.2.1-2ubuntu0.1 | Sem entrada affectedNoble no OSV consultado; readdir.so não selecionado | Ausência no OSV/manifesto não prova por si separação completa do código. Confirmar status/código antes de concluir não afetado |
| systemd16742 | libsystemd0=255.4-1ubuntu8.17 | Versão instalada corresponde àfixed oficialUbuntu | Preservar outros findings do componente; não declarar pacote universalmente livre de falhas |
| ncurses2025-69720 | libtinfo6=6.4+20240113-1ubuntu2.2 | Revisão instalada posterior àfixed oficialUbuntu2.1 na mesma linha | Escopo da correção desseID; não inferir aceite dos demais componentes |
| ACL54369 | libacl1=2.3.2-1build1.1 | OSVCanonical traz introduced0 semfixed; espelho de dados do Trivy marcaNoble ignored/backport too intrusive | Código/consumidores/pré-condições precisam de análise; ignored não significa corrigido nem autoriza o projeto a ignorar |
| util-linux76642/78408/78409/78410 | mount/libmount1/libblkid1=2.39.3-9ubuntu6.6 | OSVCanonical traz introduced0 semfixed; espelho do Trivy marcaNoble needs-triage | Avaliar código e pré-condições da compilação exata. Não afetado/corrigido ainda não demonstrados |

## Mecanismo compatível com a ausência dos cinco IDs

Consulta somente leitura em18/10 ao próprio projeto do scanner: entrada [ACL54369](https://github.com/aquasecurity/vuln-list/blob/main/ubuntu/2026/CVE-2026-54369.json) registraNoble ignored com nota backport too intrusive. As entradas [76642](https://github.com/aquasecurity/vuln-list/blob/main/ubuntu/2026/CVE-2026-76642.json), [78408](https://github.com/aquasecurity/vuln-list/blob/main/ubuntu/2026/CVE-2026-78408.json), [78409](https://github.com/aquasecurity/vuln-list/blob/main/ubuntu/2026/CVE-2026-78409.json) e [78410](https://github.com/aquasecurity/vuln-list/blob/main/ubuntu/2026/CVE-2026-78410.json) registram needs-triage.

O [importador Ubuntu do trivy-db](https://github.com/aquasecurity/trivy-db/blob/main/pkg/vulnsrc/ubuntu/ubuntu.go) consultado admite needed/deferred/pending/released e exclui esses dois estados. Isso fornece uma explicação compatível com a ausência, sem transformar estados administrativos em correção. Os links apontam ao conteúdo corrente consultado: não identificam retrospectivamente o commit do importador/dados usado para gerar a DB do run. As páginas Ubuntu/Launchpad não ficaram acessíveis nessa investigação; não foram usadas como confirmação direta desses estados.

Portanto, preservar os cinco IDs manualmente nesta conciliação. Não alterar o relatório bruto, suprimir findings ou classificar automaticamente o core como aceito por HIGH0/CRITICAL0. O padrão Debian continua separado e seu inventário anterior permanece válido somente para seu corte.

## Findings emitidos pela candidata

Todos os12IDs remanescentes estão abaixo. FixedVersion não foi informado para nenhuma ocorrência; isso não prova inexistência de correção em qualquer distribuição. Tabela registra a evidência selecionada, sem conclusão de exploração, priorização completa ou aceite implícito.

| ID | Severidade do scan | Pacote / versão instalada |
|---|---|---|
| CVE-2026-102473 | MEDIUM | dash=0.5.12-6ubuntu5 |
| CVE-2026-102474 | MEDIUM | dash=0.5.12-6ubuntu5 |
| CVE-2026-18374 | MEDIUM | libc6=2.39-0ubuntu8.9 |
| CVE-2026-89092 | MEDIUM | libc6=2.39-0ubuntu8.9 |
| CVE-2026-103111 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-86145 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-89156 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-89157 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-89158 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-89160 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-89161 | MEDIUM | libpcre2-8-0=10.42-4ubuntu2.1 |
| CVE-2026-40228 | LOW | libsystemd0=255.4-1ubuntu8.17 |

## Consumidores ACL observados no ELF real

NobleCandidate7/run39100876538, headfb236a1, mapaestático auditado em44ELFs (spec9.119). Provedorlibacl1=2.3.2-1build1.1 exporta o quarteto legado como funções públicas. Apenas os três comandos abaixo alcançamlibacl no grafoDT_NEEDED da projeção.

| Consumidor / pacote-fonte real | Imports pathname observados | Imports por FD observados | Interfaces_at observadas |
|---|---|---|---|
| cp/coreutils9.4-3ubuntu6.3 | acl_set_file, acl_delete_def_file | acl_set_fd | Nenhuma |
| mv/coreutils9.4-3ubuntu6.3 | acl_set_file, acl_delete_def_file | acl_set_fd | Nenhuma |
| sed4.9-2ubuntu0.24.04.1 | acl_get_file, acl_set_file, acl_delete_def_file | acl_get_fd, acl_set_fd | Nenhuma |

Interfaces versionadasACL_1.0, hashes/argumentos/proveniência vinculados à imagem e checkout no relatório selecionado. Imports não provam execução de cada ramo; FD presente não elimina caminhos pathname. Ausência de outras relaçõesDT_NEEDED não exclui resolução dinâmica/código incorporado. O mapa delimita consumidores para revisão de fonte, sem concluir exploração, nãoafetação ou correção.

## Fonte exata e autoridade dos consumidores ACL

Revisão estática em18/10/2026 dos arquivos originais e das séries de patches das fontes oficiais [coreutils9.4-3ubuntu6.3](https://archive.ubuntu.com/ubuntu/pool/main/c/coreutils/coreutils_9.4-3ubuntu6.3.dsc) e [sed4.9-2ubuntu0.24.04.1](https://archive.ubuntu.com/ubuntu/pool/main/s/sed/sed_4.9-2ubuntu0.24.04.1.dsc). Baseline, tamanhos e SHA256 dos quatro arquivos estão em spec9.120. A relação com os ELFs é documental por pacote-fonte/versão; não houve compilação, execução das fontes, verificação GPG da assinatura ou reprodução do build Ubuntu.

| Recorte da fonte original | Evidência específica | Conclusão e limite |
|---|---|---|
| coreutils `src/copy.c:3290–3305` | Ramos de preservação/movimentação passam descritores `-1` a `copy_acl`; outros ramos passam `-1` a `set_acl` | Há chamadas por nome na fonte. O teste anterior de `dest_is_symlink` não demonstra estabilidade dos ancestrais do caminho |
| coreutils `lib/qcopy-acl.c:53–87` | Se `USE_XATTR` estiver definido, usa `attr_copy_file` quando um descritor é inválido; caso contrário usa os helpers de permissões | Não atribuir toda chamada de `copy_acl` a libacl. O `config.h` exato do build não foi obtido; imports ELF não selecionam sozinhos esse ramo |
| Helpers POSIX de coreutils e sed, `lib/get-permissions.c:45–63` e `lib/set-permissions.c:513–540` | ACL de acesso prefere FD quando disponível; fallback usa nome. ACL padrão de diretório continua usando nome | FDs não substituem universalmente as interfaces pathname. Código condicional não prova execução de cada ramo no binário |
| sed `sed/execute.c:592–596,646–675` e `lib/qcopy-acl.c:39–50` | Edição in-place exige entrada regular e passa `fileno` de entrada/saída ao helper | O callsite fornece FDs; nesse percurso regular o ramo de ACL padrão de diretório não se aplica. Isso não certifica todas as operações de sed nem remove seus imports pathname |

As dez entradas da série de patches coreutils não alteram `src/copy.c` nem os helpers ACL analisados. `cp-n.diff` altera seleção de comportamento de `cp -n`, não esses helpers. A única entrada da série sed, `CVE-2026-5958.patch`, ajusta o nome usado na abertura após resolução de link, sem alterar a chamada de `copy_acl` ou os helpers. `debian/rules` não fornece o `config.h` gerado nem prova sozinho todas as opções efetivas do build. Não se conclui que a biblioteca ACL inteira esteja corrigida a partir dessa revisão de consumidores.

No worker, quem controla caminhos sintéticos opera como UID10001, sem capabilities e sem autoridade adicional demonstrada sobre arquivos de outro proprietário. Isso limita consequências; não corrige o código nem comprova ausência de exploração. No controlador, a autoridade é a do runner/daemon confiáveis: `containment.py:253–254` prepara as duas ACLs antes do primeiro worker (`:274`); o retorno normal de `_worker` exige saída bem-sucedida e executa remoção própria no `finally` (`:217–229`) antes das leituras posteriores. Não há reaplicação de ACL pelo controlador enquanto esse worker executa neste protocolo. Essa ordem não autoriza operações futuras em caminhos mutáveis nem comprova contenção do agente atual.

Resultado: o recorte `sed -i` oferece evidência de uso de FDs, mas não há demonstração suficiente para classificar ACL54369 como corrigido ou não afetado na composição inteira. Preservar a pendência e a baseline candidata. Um teste que apenas copie ACL com sucesso não responderia à questão de autoridade/corrida e não é critério de fechamento.

## Próximo aceite

Primeiro analisar ACL54369 na biblioteca/consumidores exatos e nos privilégios/mounts realmente impostos, sem confundir mitigação com correção. Depois resolver os quatro util-linux e a lacuna40553, além de registrar triagem/prioridade dos12IDs remanescentes. Conclusão de código não afetado exige evidência específica; alteração de risco/escopo ou manutenção própria exige direção de João.

A candidata permanece sem promoção automática. Esta conciliação não encerra AJ-HOST/NET/TOOLS completos, Desktop, revisão histórica de publicação ou ensaio pessoal de João. Frontend/painel seguem depois da v1.
