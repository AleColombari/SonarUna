# Trabalho no SonarUna

- Branch de trabalho autorizada: `Desenvolvimento-Alexandre` (nome exato).
- A branch padrão do GitHub é `main`. Nunca alterar essa configuração para a branch de trabalho.
- Alexandre autorizou commits e pushes nessa branch sempre que necessários.
- Merges, integração com o trabalho do colega e alterações na `main` são decididos pelos desenvolvedores. Não efetuar merges nem push para `main` sem nova instrução explícita.
- Manter o protótipo Android nativo simples, acessível e em português do Brasil.
- A primeira versão coleta localização aproximada, confirma origem e destino por voz e mantém esses dados somente em memória. Não calcula rotas nem fornece navegação.
- Não versionar SDK, JDK, `local.properties`, credenciais, APKs ou dados pessoais de testes.
- Antes de publicar alterações de código, executar `assembleDebug`, `testDebugUnitTest` e `lintDebug` quando o ambiente permitir. Documentar honestamente verificações que exigem celular físico e TalkBack.
