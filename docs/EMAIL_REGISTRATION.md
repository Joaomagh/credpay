# Cadastro com e-mail e senha — C09

Solicitado por João em28/11/2026. Google continua principal; cadastro próprio é alternativa. Uma única frente: concluir C09 e retomar C02. Sem provedor ou biblioteca adicional.

- Nova conta vazia, com UUID gerado no servidor e e-mail de login exclusivo, normalizado em minúsculas/trim. Não remover pontos/sufixos `+`. Primeira versão aceita endereços ASCII usuais com domínio público, até254 caracteres e64 na parte local. Google continua vinculado apenas por issuer/subject; não mesclar contas pelo e-mail.
- Senha de8 a128 pontos de código, sem composição obrigatória, sem trim ou truncamento; preservar espaços. Limitar comprimento UTF-16 a256 antes de contar pontos de código. Hash PBKDF2-HMAC-SHA256/600.000 iterações/salt16 da baseline existente. Nunca armazenar senha em texto ou logar corpo/DTO. Mínimo8 escolhido explicitamente por João; a recomendação sem MFA é15, trade-off registrado, sem alegar conformidade com esse mínimo. Referência: [OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).
- `POST /auth/registro` recebe somente email/senha JSON. Anônimo com CSRF; query/campos desconhecidos/JSON inválido400, corpo acima8192 bytes413, inválidos422, limite429 com Retry-After. Limite próprio5/IP/min e10/global/min antes de CSRF/hash. Nenhum UUID ou e-mail ecoado.
- Sucesso ou e-mail já cadastrado:202 com resposta genérica, mesmo hash calculado e sem sobrescrever conta/senha existente. A UI orienta entrar com a senha atual, sem autenticar automaticamente. Cadastro concorrente deve produzir uma única conta. Falhas de banco não viram sucesso.
- `POST /auth/login` passa a aceitar e-mail normalizado ou usuário legado. Sessão e CSRF preservados; usuário técnico não é mostrado na interface. Conta Google sem senha continua sem login local. `/auth/registro` só é permitido no perfil de sessão; legado anônimo não abre cadastro por acidente.
- Interface: opção Criar conta com e-mail, campos e-mail/senha/confirmação, ajuda de senha e estado de envio/erro; senha não vai para storage. Tema permanece alternável com ícone e nome acessível.

Ainda não há confirmação de posse do e-mail, entrega de mensagens, recuperação de senha, MFA ou consulta a listas de senhas vazadas. E-mail é identificador de login não verificado neste incremento; não alegar propriedade nem usar para unir contas. Resolver envio/verificação/recuperação e política de senhas comprometidas antes de liberar cadastro para uso real. A publicação do portfólio conserva aceites próprios; este contrato não provisiona serviço externo nem autoriza dados reais.

Estado: contrato refinado; implementação e evidências serão registradas em spec/task/backlog.
