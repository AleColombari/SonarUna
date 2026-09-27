# Validação inicial do SonarUna

Data: 27/09/2026. Código validado: `1ff3077` na branch `Desenvolvimento-Alexandre`. A branch padrão é `main`; nenhum código do protótipo foi integrado nela.

## Resultados

| Verificação | Resultado |
| --- | --- |
| `assembleDebug` | APK gerado com sucesso |
| `testDebugUnitTest` | 14 testes, zero falhas, zero erros, zero ignorados |
| `lintDebug` | Zero erros; 12 avisos locais |
| `assembleDebugAndroidTest` | APK de testes gerado com sucesso |
| Instrumentação via AndroidJUnitRunner | 3 testes aprovados no emulador API 36 |
| GitHub Actions | Build, testes unitários, Lint e compilação dos testes de interface aprovados |
| Instalação e abertura | APKs instalados e MainActivity aberta com sucesso no emulador |
| Inspeção visual | Tela branca e botão central em retrato e paisagem; fonte do sistema em 200% na verificação de paisagem |
| Identidade do APK | SonarUna, `com.example.sonaruna`, versão 0.1.0, mínimo API 26 e destino API 36 |

[Execução aprovada no GitHub](https://github.com/AleColombari/SonarUna/actions/runs/36347235623).

Os avisos do Lint são recomendações de atualização de versões estáveis e indicação de metadado adicional de backup para versões antigas do Android. O backup já está desabilitado por `allowBackup=false`, e as regras do Android 12+ excluem os dados de backup e transferência. Não foi criado baseline nem desabilitada verificação de erros para obter aprovação.

Os testes unitários cobrem o fluxo completo da máquina de estados, armazenamento em memória, confirmações positivas e negativas, acentos e pontuação, respostas vazias e ambíguas, correção de origem/destino, eventos fora da etapa esperada e reinício com localização recente, antiga ou futura.

Os testes de interface verificam um único botão com papel e nome acessíveis, o acionamento de cancelamento no estado de escuta e o bloqueio durante a fala. Eles exercitam a interface com estados controlados; não simulam o resultado de um teste real de microfone, localização ou TalkBack.

## Ambiente e limitações

Build local no Windows com JDK 17, Gradle 8.13, SDK 36 e AGP 8.13.2. A automação repetiu o build em Linux no GitHub. O emulador usa a imagem Google APIs x86_64 do Android 16, com perfil Pixel 4 e aceleração WHPX.

A primeira tentativa de instrumentação não chegou a executar os testes porque o processo excedeu o tempo de inicialização. O ambiente também apresentou travamentos de inicialização em Bluetooth, Google Play services e componentes do sistema Android. Depois de estabilizar o ambiente e compilar previamente os pacotes de teste, a execução terminou com `OK (3 tests)` em 34,436 segundos.

O aplicativo abriu com a tela branca e o botão central. A árvore de acessibilidade mostrou um único botão acionável do aplicativo. A síntese pt-BR não ficou disponível nesse ambiente; o botão expôs a mensagem de recuperação por acessibilidade. Isso verifica o tratamento desse estado e não comprova reprodução de voz no dispositivo.

Evidências locais, não versionadas, ficam em `build/qa/`: saída da instrumentação, capturas da tela e árvore de acessibilidade. Os relatórios de build ficam em `app/build/reports/`, e o APK em `app/build/outputs/apk/debug/app-debug.apk`.

Permanecem pendentes os testes em celular físico com TalkBack, microfone, síntese pt-BR, permissões reais, localização aproximada e vibração. O [roteiro manual](TESTES_MANUAIS.md) registra esses casos sem apresentá-los como aprovados. Nesta versão, o aplicativo não calcula rotas nem orienta deslocamentos.

## Arquivos entregues

O repositório estava vazio. Foram criados o projeto Gradle e wrapper, manifesto e recursos Android, `MainActivity`, `MainScreen`, `MainViewModel`, a máquina de estados em `conversation/`, os quatro serviços em `platform/`, testes unitários e de interface, a automação do GitHub e a documentação. Não havia aplicativo anterior a preservar ou modificar.
