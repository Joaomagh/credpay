# Sandbox-core diagnóstico — construção em andamento

Contrato aprovado: spec.md §4.3. Debian slim, UID/GID10001, ferramentas diagnósticas limitadas, sem Java/Git/Maven ou cliente do agente. B08.7 ainda não tem aceite de contenção.

## B08.7a: inventário da origem

`baseline.env` fixa índice e digest linux/amd64 do Debian13/trixie-slim, consultados em17/10/2026 na [metadata oficial](https://hub.docker.com/v2/repositories/library/debian/tags/trixie-slim). Alterar a tag não atualiza o digest automaticamente.

`source_ci.py` roda apenas no runner Linux hospedado do GitHub. O controlador confiável usa Python3 preexistente/stdlib e Docker do runner; não instala Python nem ferramentas dentro do container. Confere que índice e plataforma resolvem o mesmo image ID, recusa volumes declarados, cria um container próprio com usuário10001/rede none/rootfsRO/cap-dropALL/NNP e limites de4.3, exporta seu filesystem e remove somente esse container. Confere estado `created`, `Running=false` e `StartedAt` zerado antes/depois; **nenhum processo é iniciado**. Root/controller/daemon do runner continuam na base de confiança.

`inventory_source.py` lê o tar sem extrair ou executar arquivos. Registra pacotes instalados, caminhos/tipos/modos/UID/GID, SHA256 dos arquivos, links, bits setuid/setgid e presença de metadata de capabilities. Resolve aliases das20 ferramentas aprovadas, inclusive usrmerge, sem seguir links no host. Não comprova que cada ferramenta funciona. Limites:100mil entradas,128MiB por arquivo,1GiB declarado; estado dpkg obrigatório. Recusa caminhos absolutos/`..`, duplicatas, links que escapem da raiz virtual, ciclos como ferramenta disponível, pacote não instalado e origem divergente.

O relatório associa checkout, imagem/plataforma, UTC e SHA256 do tar. O CI publica apenas `source-inventory.json` e `SUMMARY.md` por7dias; não publica tar, Config/Env do Docker, conteúdo dos arquivos ou logs integrais. Hashes dos arquivos injetados pelo daemon (`etc/hostname`, `etc/hosts`, `etc/resolv.conf`, `.dockerenv`) são omitidos. Nenhum dado pessoal/canário real é usado.

Isso é **SOURCE_ONLY_NOT_CORE_ACCEPTANCE**: não é imagem final reduzida, scan de vulnerabilidades, prova de execução como10001, AI-Jail, allowlist de egress ou contenção do agente. Uma origem pode conter comandos/pacotes não aprovados para o core. Próximo incremento deve inventariar/reduzir executáveis e dependências, definir o mecanismo de rede controlado e comprovar os negativos AJ-* de4.3. GNU awk ainda é apenas candidato ao cliente TCP/UDP.

## Controles sem Docker

```sh
python3 -I -B -m unittest discover -s infra/agent-sandbox -p 'test_*.py'
```

No Windows desta sessão foi usado o Python3.12.14 já fornecido pelo runtime do workspace, com `-I -B`; nenhuma instalação global. Fixtures sintéticas ficam exclusivamente em `.local/agent-sandbox/controls`, com raiz resolvida/conferida antes da limpeza. Docker local não foi executado. Execução real somente pelo workflow `Sandbox Core CI`.

## B08.7b: construção mínima integrada

`packages.lock.json` registra seis pacotes Debian amd64 por versão completa/bytes/SHA256 antes do download. Origem: índice oficial conferido contra Release viaHTTPS/digest (não se declara verificação GPG adicional). GNU awk implementa o nome aprovado `awk`; `ps` vem deprocps. Pacotes não são instalados integralmente: preparação fixa/confiável usa `dpkg-deb` da base em build sem rede, confere Package/Version/Architecture e extrai arquivos sem maintainer scripts. Esta preparação ocorre como root no builder confiável; o worker final sempre usa10001. Contextos são diretórios próprios sem home/socket/credenciais; `.dockerignore` limita os arquivos enviados.

O controlador usa Python/stdlib e `readelf` preexistentes no runner, sem instalar ferramentas. `project_core.py` segue PT_INTERP/DT_NEEDED sem `ldd` ou execução de candidatos, recusa RPATH/RUNPATH e bibliotecas ausentes. Copia os20 comandos com seus nomes aprovados, loader/bibliotecas necessários, configuração mínima de identidade/C locale e copyrights/licenças. Comandos/loader ficam0555, bibliotecas/dados0444 e o placeholder vazio do engine0755, sem bits setuid/setgid/capabilities herdadas. Manifesto associa cada arquivo à origem e aos componentes Debian parciais; não conserva banco dpkg afirmando instalação íntegra dos78 pacotes.

O Dockerfile final mantém FROMDebianpin e poda excedentes em uma única chamada rm dentro de RUN sem rede. Preserva pontos que BuildKit monta em `/etc`, sem suprimir falhas. Camadas inferiores conservam conteúdo público da ancestralidade; nada é publicado em registry. Adiciona tar gerado exclusivamente pelo controlador, define10001 e env explícito. `/dev/console` é um arquivo vazio0755 criado pelo engine e conferido por tamanho/hash para o ponto de montagem padrão do engine; não é um device ou executável da imagem. Diretórios de montagem virtuais não concedem acesso a devices do host.

O CI compara arquivos/tipos/hashes/modos/UID/GID e diretórios/links do export final ao manifesto; extras, inclusive executáveis, falham. Depois executa os20 comandos com rede none/raizRO/capdropALL/NNP/limites e `/tmp` tmpfs. Smoke ainda não prova TCP/UDP do awk, os controles negativos AJ-*, recursos sob excesso, allowlist de egress, vulnerabilidades ou compatibilidade DockerDesktop. Isso continua **CORE_BUILD_NOT_CONTAINMENT_ACCEPTANCE**. Publicação limitada a inventários/summary, sem tar/Config/Env/saídas integrais. Nenhuma topologia de agente aprovada por esta construção.

## B08.7c: primeiros negativos de contenção

`containment.py` passou na primeira validação real do Sandbox Core CI12, com34 controles locais/Linux; evidências e limites em spec§9.102. Monta somente uma fixture própria como `/workspace`. O controlador dá ACL apenas ao UID10001 usando `setfacl` preexistente, mantém o pai0700, cria canário externo/symlink falsos e repositório Git sintético com global/sistema/hooks/templates desativados. Não monta checkout real/home/socket nem instala Git no core. Falta de Git/setfacl bloqueia o job; não gera PASS nem instalação automática.

Antes de executar, valida imagem/ID/label, UID/env allowlist, raizRO/capdrop/NNP, namespaces privados/rede none, limites exatos e único bind próprio. O protocolo deve produzir todas as observações esperadas, sem duplicatas, campos extras ou resultados falsos. Testes locais demonstram recusa de configuração/observação divergentes.

Os negativos cobrem canário externo inacessível por path/symlink, escrita interna real/ownership, EROFS em diretório da imagem owned10001 (`/readonly-probe`), ELF aprovado copiado com hash/bits555 ao/tmp negado126 por noexec, mountinfo/tmpfs256MiB e descarte. Ambiente só com nomes previstos, sentinela falsa exclusiva do controlador ausente, UID/capabilities/NNP observados e utilitário mount negando operação privilegiada com alvo/fstype válidos. Primeiro worker permanece vivo por flag de fixture, com espera externa limitada: observador restrito comprova device+inode de PIDnamespace diferentes durante vidas sobrepostas. Só então o controlador libera saída, remove o primeiro e recria; bind persiste e temporários desaparecem. Não comparar inode depois da destruição, pois não identifica objetos ao longo do tempo. Filho vivo foi observado antes da saídaPID1, mas seu término não é inspecionado independentemente no host. Git fora do core detecta somente a alteração-canário autorizada.

Relatório inclui comandos (somente dados fictícios), hashes/configuração selecionada/UTC/plataforma/exit codes/controles positivos. Não publica env real, Config completo ou logs brutos. Cgroups são lidos, sem ainda provar excesso; AJ-HOST/NET/RES/TOOLS completos, scan e DockerDesktop continuam pendentes. Nenhuma prova de contenção do agente atual.

## B08.7d.1: proveniência fonte Debian

O inventário preserva nome/versão binários e sourceName/sourceVersion completos. Source ausente usa identidade binária; nome sem versão usa a versão binária; versão explícita pode diferir em um rebuild. O builder confiável exporta o control real dos seis debs já fixados por hash; o controlador recusa divergência de nome/versão/arquitetura contra lock antes da projeção. Source inválido interrompe o inventário.

Esses dados acompanham somente os componentes selecionados no manifesto parcial. Nenhum banco dpkg é inventado, nenhum pacote é instalado integralmente e nenhum scanner foi executado por esta mudança. Vulnerabilidades continuam pendentes; contrato/evidências em spec§9.103.

## B08.7d.2: advisories dos componentes parciais

scan_core.py gera CycloneDX próprio a partir da projeção auditada, com OS Debian13 e origem fonte preservada. Reutiliza Trivy0.75.0 pinado por SHA256, somente no controlador CI e em diretório próprio; o worker permanece com os20 comandos. Config/ignore vazios e ambiente mínimo impedem herança de configuração. Não usa socket ou banco dpkg artificial.

Cobertura exige todos os componentes e nenhuma duplicata/extra, versões/arquitetura/fontes iguais, DB presente/temporalmente coerente/não vencida e severidades reconhecidas. Todas as severidades e achados sem correção são preservados. HIGH/CRITICAL marcam baseline como pendente de remediação/disposição, sem omitir o inventário.

O relatório selecionado em core-inventory.json inclui componentScan (cobertura/achados/DB/hashes/imagem/checkout/comando/UTC/limites). SBOM terceiro pode causar detecção imprecisa: warning registrado, sem declarar autoria Trivy. Não cobre imagem completa/camadas inferiores/kernel/controlador/conteúdo futuro do workspace nem demonstra exploração; não aceita sandbox completo. spec§9.104.
