# Proposta de remediação do core diagnóstico

Estado em18/10/2026: proposta para decisão de João, sem mudança de imagem, instalação ou aceite de risco. Aplicativos Java conservam a plataforma já migrada; este documento trata somente do core diagnóstico Debian.

## Problema e recomendação

A baseline auditada do core contém HIGH17/CRITICAL2 em11IDs distintos. CPU/memória/PIDs/TCP/UDP comprovados não corrigem essas vulnerabilidades. Não existe atualização Debian corrigida confirmada para gawk40467/40468 no corte consultado; manter a mesma imagem não permite declarar a segurança encerrada.

Recomendo avaliar uma migração coerente do core inteiro para pacotes oficiais Ubuntu24.04Noble, começando pelo inventário e pelo GNUawk corrigido. Ela evita manter patches próprios e tem correções oficiais para parte dos achados. **Não é solução completa confirmada:** os registros oficiais abaixo ainda não indicam versão corrigida de ACL e util-linux. A avaliação pode reprovar a candidata; nenhuma redução de contagem equivale a aceite.

| Caminho | Benefício | Custo / limite |
|---|---|---|
| Avaliar UbuntuNoble oficial — recomendado para a próxima investigação | GNUawk corrigido na mesma série5.2.1; systemd/ncurses também possuem versão corrigida publicada; sem fork próprio | Muda a distribuição aprovada; requer novos pins, projeção ELF, proveniência, identidade OS no scanner e controles. Não resolve automaticamente ACL/util-linux |
| Manter Debian e construir gawk corrigido | Preserva distribuição e permite corrigir o código prioritário | Projeto passa a manter build/backport, assinatura/origem/fontes/patches/toolchain/licenças/regressões e identidade própria. Scanner não pode tratá-lo como pacote oficial Debian corrigido; outros achados permanecem |
| Manter baseline atual sem mudança | Preserva os experimentos diagnósticos existentes | Segurança permanece sem aceite e v1 completa não é encerrada. Não é aceitação temporária de risco |

A divulgação coordenada [CERT Polska](https://cert.pl/en/posts/2026/07/CVE-2026-40467/) informa correções gawk upstream5.4.1. Os trackers [Debian40467](https://security-tracker.debian.org/tracker/CVE-2026-40467) e [Debian40468](https://security-tracker.debian.org/tracker/CVE-2026-40468) ainda listavam trixie5.2.1-2/sid5.3.2-1 vulneráveis em17/10. O [anúncio oficial Ubuntu](https://lists.ubuntu.com/archives/noble-changes/2026-July/057555.html) documenta backport40467/40468/40469; isso não autoriza usar DEB Ubuntu em Debian.

## Comparativo dos11IDs em dados oficiais Ubuntu

Consulta somente leitura em18/10 ao [repositório de avisos mantido pela Canonical](https://github.com/canonical/ubuntu-security-notices/tree/main/osv/cve). Campo examinado: affected/package/ecosystem Ubuntu:24.04:LTS e ranges/events. Esses registros descrevem pacotes da distribuição; ainda não provam conteúdo da imagem candidata, aplicabilidade ao ELF reduzido ou resultado Trivy. Ausência de evento fixed não é versão corrigida; ausência da distribuição na lista affected não é prova independente de código ausente.

| ID | Pacote-fonte | Resultado encontrado para Noble |
|---|---|---|
| [40467](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-40467.json) | gawk | fixed1:5.2.1-2ubuntu0.1 |
| [40468](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-40468.json) | gawk | fixed1:5.2.1-2ubuntu0.1 |
| [40469](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-40469.json) | gawk | fixed1:5.2.1-2ubuntu0.1 |
| [40553](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-40553.json) | gawk | Sem entrada affected Noble no registro consultado; confirmar status e código antes de concluir |
| [54369](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-54369.json) | acl | introduced0, sem evento fixed; consumidores/APIs também exigem análise |
| [76642](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-76642.json) | util-linux | introduced0, sem evento fixed |
| [78408](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-78408.json) | util-linux | introduced0, sem evento fixed |
| [78409](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-78409.json) | util-linux | introduced0, sem evento fixed |
| [78410](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-78410.json) | util-linux | introduced0, sem evento fixed |
| [16742](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2026/UBUNTU-CVE-2026-16742.json) | systemd | fixed255.4-1ubuntu8.17 |
| [2025-69720](https://github.com/canonical/ubuntu-security-notices/blob/main/osv/cve/2025/UBUNTU-CVE-2025-69720.json) | ncurses | fixed6.4+20240113-1ubuntu2.1 |

## Incremento proposto, se João escolher a avaliação Noble

1. Registrar baseline antes de download: imagem oficial/index/platform/linuxamd64, digests, metadados oficiais de pacotes, versões/arquiteturas/tamanhos/SHA256, suporte e licenças. Não usar imagem móvel como identidade final nem misturar pacotes de distribuições.
2. Produzir inventário candidato em CI hospedado, primeiro sem iniciar processo. Preservar20 comandos, usuário10001, ambiente mínimo, rootfsRO, rede none, caps vazias, NNP, limites e binds fictícios próprios. Nenhum daemon/socket do host no worker ou instalação local.
3. Sob TDD, adaptar projeção e scanner para identidade real Ubuntu24.04; rejeitar divergências OS/binário/fonte/cobertura. Hoje scan_core.py exige Debian13: somente trocar FROM seria inválido. Não criar manifesto Debian fictício nem remover findings.
4. Repetir smoke/export, filesystem/privilégios/ambiente/ciclo de vida, CPU/memória/PIDs e TCP/UDP na candidata. Registrar SHA/imageID/UTC/configurações/comandos e artefatos selecionados; revisar antes de integração.
5. Comparar todos os componentes e achados novos/anteriores. Classificar a candidata como aceita ou reprovada com justificativa; correção de gawk não fecha os outros IDs. Em caso de reprovação, preservar diagnóstico e preparar próxima decisão, sem waiver implícito.

Arquivos afetados numa implementação: infra/agent-sandbox/baseline.env, packages.lock.json, source_ci.py, Dockerfiles, project_core.py, scan_core.py e seus testes/documentação. O recorte exato vem do inventário, sem mudança nos serviços Java. Manifesto parcial e cobertura do scanner continuam obrigatórios.

## Limite da decisão

A [triagem vigente](../infra/agent-sandbox/TRIAGE.md) determina: “Não misturar pacotes sid em Debian13, compilar patches ou trocar distribuição automaticamente.” Escolher a rota modifica a baseline explicitamente aprovada em spec4.3 ou cria manutenção própria; por isso depende da direção de João. Autorizar a avaliação Noble não aceita risco, não promove automaticamente a candidata nem autoriza implantação externa, alteração de segurança local, ferramentas adicionais no worker ou uso do agente dentro dele.
