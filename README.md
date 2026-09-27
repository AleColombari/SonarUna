# SonarUna

Protótipo acadêmico Android para um projeto da **UNAERP, em Ribeirão Preto**. Esta primeira versão valida uma interação acessível por voz: obtém a localização aproximada do celular, pergunta onde a pessoa está, pergunta para onde deseja ir e confirma as duas respostas.

**O aplicativo ainda não calcula rotas nem fornece instruções de navegação. Não substitui bengala, cão-guia, orientação humana ou outras ferramentas de mobilidade.**

## O que esta versão faz

- Exibe uma tela branca com um único botão circular preto, grande e centralizado, com ícone branco de microfone.
- Solicita acesso ao microfone e à localização depois de explicar sua finalidade por voz.
- Obtém latitude, longitude, precisão e horário de uma leitura aproximada; as coordenadas não aparecem na tela.
- Usa síntese e reconhecimento de voz em português do Brasil.
- Confirma origem e destino por uma máquina de estados simples.
- Mantém os dados da conversa apenas em memória durante a sessão.
- Permite cancelar a escuta, corrigir respostas e tentar novamente após erros.
- Oferece descrições de ação para TalkBack e vibrações de início, sucesso e erro.

Não há mapa, salas cadastradas, grafo, A*, cálculo de rota, orientação de percurso, detecção de chegada, perímetro da universidade, Google Maps, banco de dados, backend, login ou sensores externos nesta versão. Os nomes de lugares são apenas o conteúdo falado pela pessoa, sem validação contra um cadastro.

## Requisitos e tecnologias

Aplicativo: `SonarUna`, versão inicial `0.1.0`. Namespace e identificador Android: `com.example.sonaruna`.

| Componente | Versão/configuração |
| --- | --- |
| Android mínimo | Android 8.0, API 26 |
| SDK de compilação / destino | 36 / 36 |
| Java | JDK 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.13 |
| Kotlin e plugin Compose Compiler | 2.3.21 |
| Compose BOM | 2025.10.01 |
| Material 3 | 1.4.0, gerenciado pelo BOM |
| Activity Compose | 1.10.1 |
| Lifecycle / ViewModel Compose | 2.9.4 |
| AndroidX Core | 1.17.0 |
| Google Play services Location | 21.3.0 |
| Fragment (dependência indireta do Play services) | 1.8.9, para compatibilidade com as permissões modernas |
| Kotlin Coroutines Android / Play services | 1.10.2 |
| Estado da interface | ViewModel e StateFlow |
| Voz | SpeechRecognizer e TextToSpeech nativos |

Para o fluxo completo, o aparelho precisa ter Google Play services disponível, localização ativada, microfone funcional, um serviço de reconhecimento de voz e síntese de voz com português do Brasil. A disponibilidade de reconhecimento local e de modelos de idioma varia por aparelho e provedor.

Recomenda-se **testar em celular físico com TalkBack**. Um emulador com Google APIs/Google Play e localização configurada ajuda a verificar a interface, mas não comprova a experiência real de voz, vibração, localização interna ou leitor de tela.

## Abrir no Android Studio

1. Clone o repositório e selecione a branch de trabalho `Desenvolvimento-Alexandre`.
2. Abra a pasta raiz `SonarUna` no Android Studio com suporte ao AGP 8.13.2.
3. Configure o Gradle JDK como JDK 17. Pelo SDK Manager, instale Android SDK Platform 36 e as ferramentas solicitadas pela sincronização.
4. Aguarde a sincronização e o download das dependências. Não são necessárias chaves de API nem cadastro em serviço externo.
5. Conecte um celular Android 8.0 ou superior com depuração USB autorizada e execute a configuração `app`.
6. Instale ou habilite a voz pt-BR e o serviço de reconhecimento nas configurações do dispositivo, se necessário.

O Android Studio gera `local.properties` com o caminho do SDK de cada máquina. Esse arquivo, SDKs, JDKs e arquivos gerados não devem ser versionados.

## Compilar e executar os testes

Na raiz do repositório, com JDK 17 e SDK configurados, use o wrapper incluído no projeto.

Windows / PowerShell:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Linux / macOS:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

O APK de debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. Os relatórios ficam em `app/build/reports/tests/testDebugUnitTest/` e `app/build/reports/lint-results-debug.html`.

Com um dispositivo ou emulador conectado, execute também os testes de interface:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

No Linux/macOS, use `./gradlew connectedDebugAndroidTest`. A compilação dos testes de interface, sem executá-los em um dispositivo, pode ser feita com `assembleDebugAndroidTest`.

Os testes unitários verificam transições da conversa, confirmações positivas e negativas, normalização de acentos, respostas desconhecidas ou contraditórias, silêncio, preservação dos locais falados, reinício e validade da localização reutilizada. Os testes Compose verificam a presença de um único botão com semântica acessível, o cancelamento da escuta e o bloqueio temporário durante a fala.

### Estado das verificações

| Verificação | Situação |
| --- | --- |
| Build de debug | Aprovado localmente e no GitHub em 27/09/2026 |
| Testes unitários | 14 aprovados, sem falhas |
| Android Lint | Aprovado: nenhum erro; 12 avisos locais sobre versões e metadados de backup |
| Testes de interface em dispositivo/emulador | 3 aprovados no emulador Android 16 / API 36 |
| Fluxo completo de voz e permissões em celular | A executar |
| TalkBack, vibração e configurações de acessibilidade | A executar |

Os testes automatizados não substituem a avaliação em aparelho real. Use o [roteiro de testes manuais](docs/TESTES_MANUAIS.md) e registre o aparelho, as versões e os resultados antes de considerar a experiência acessível validada.

Os resultados e limites dessa verificação estão em [VALIDACAO.md](docs/VALIDACAO.md). A imagem do emulador não disponibilizou voz pt-BR utilizável; o aplicativo apresentou a descrição acessível de recuperação. O fluxo real de voz e localização ainda precisa ser verificado em celular.

O workflow [Android](.github/workflows/android.yml) repete o build, os testes unitários, o Lint e a compilação dos testes de interface em pushes na branch de trabalho e em pull requests. Ele disponibiliza o APK e relatórios como artefato por 14 dias; não executa testes de voz ou TalkBack.

## Fluxo da conversa

1. **Inicialização:** configura a voz pt-BR. O microfone permanece desligado.
2. **Permissões:** explica a necessidade dos acessos e apresenta os diálogos do Android.
3. **Localização:** busca uma leitura aproximada. GPS não identifica automaticamente a sala nem garante precisão em ambientes internos.
4. **Origem:** pede que a pessoa toque no botão e diga onde está.
5. **Confirmação da origem:** repete a resposta e pede um novo toque seguido de “sim” ou “não”. Uma negativa permite informar a origem novamente.
6. **Destino:** pede um novo toque para dizer aonde deseja ir.
7. **Confirmação do destino:** repete a resposta e solicita confirmação. Uma negativa permite corrigir apenas o destino.
8. **Conclusão:** fala a origem e o destino confirmados e explica que o cálculo de rota ainda não está disponível.
9. **Reinício:** o botão passa a oferecer “Iniciar uma nova orientação”. Um toque limpa origem e destino. Uma leitura de localização com até dois minutos pode ser reutilizada; leituras antigas, ausentes ou com horário futuro exigem nova obtenção.

Cada resposta exige uma ativação explícita do botão. O aplicativo nunca abre o microfone automaticamente após uma pergunta, um erro ou o retorno do segundo plano. Com TalkBack, a ativação usa o gesto normal do leitor de tela.

Durante a escuta, o botão anuncia “Ouvindo. Toque para cancelar” e permite interrompê-la. Durante as instruções faladas e operações de inicialização, o botão aguarda a conclusão. A síntese do aplicativo é encerrada antes da captura de áudio; o reconhecedor é encerrado antes de uma nova instrução falada.

Confirmações aceitam, entre outras expressões, “sim”, “correto”, “isso”, “está certo” e “confirmo”; negativas incluem “não”, “errado”, “não está correto” e “corrigir”. A comparação ignora maiúsculas, acentos, espaços extras e pontuação. Frases não reconhecidas como uma confirmação completa mantêm a etapa atual e pedem uma nova resposta. O conteúdo de origem/destino preserva as palavras, removendo apenas espaços desnecessários.

Os estados da conversa são `INITIALIZING`, `PERMISSIONS`, `LOCATING`, `ORIGIN`, `CONFIRM_ORIGIN`, `DESTINATION`, `CONFIRM_DESTINATION`, `COMPLETE` e `ERROR`. Estados de fala, escuta e trabalho controlam a disponibilidade e a descrição do botão. Erros preservam a possibilidade de recuperação pela mesma ação central.

## Permissões e recuperação

| Permissão | Uso |
| --- | --- |
| `ACCESS_COARSE_LOCATION` | Obter a posição aproximada em primeiro plano |
| `ACCESS_FINE_LOCATION` | Declarada e solicitada junto com a aproximada; a versão aceita somente a aproximada |
| `RECORD_AUDIO` | Reconhecer uma resposta depois do toque no botão |
| `VIBRATE` | Feedback tátil; permissão normal, sem diálogo em tempo de execução |

Não há acesso à localização em segundo plano. A requisição utiliza prioridade equilibrada, granularidade aproximada, cache de até 30 segundos e uma tentativa de cerca de 20 segundos.

Uma negativa de permissão recebe explicação por voz e permite nova solicitação. Quando o Android não permite apresentar o pedido novamente, o botão abre as configurações do aplicativo. Ao voltar, o estado das permissões é conferido novamente. Localização desativada, serviço ausente, silêncio, microfone indisponível e falha de rede do reconhecedor também recebem mensagens e uma ação de nova tentativa.

Se a síntese pt-BR não puder iniciar, o aplicativo tenta anunciar o problema por acessibilidade para o TalkBack e mantém a instrução de recuperação na descrição do botão. **Esse recurso depende de um leitor de tela funcional: não garante comunicação falada quando nenhum mecanismo de voz do dispositivo funciona.** O fluxo normal aguarda a correção da voz e uma nova tentativa; não muda silenciosamente para inglês.

## Organização e ciclo de vida

```text
app/src/main/java/com/example/sonaruna/
├── MainActivity.kt                 # Permissões, configurações e ciclo de vida da tela
├── MainScreen.kt                   # Um único botão Compose acessível
├── MainViewModel.kt                # Coordenação da sessão, serviços e StateFlow
├── conversation/
│   ├── ConversationState.kt        # Estados, localização e mensagens lógicas
│   └── ConversationEngine.kt       # Máquina de estados independente de Android
└── platform/
    ├── LocationRepository.kt       # FusedLocationProviderClient
    ├── SpeechRecognitionManager.kt # Uma resposta por ativação
    ├── TextToSpeechManager.kt      # Inicialização e conclusão das falas
    └── HapticFeedback.kt           # Padrões simples de vibração
```

As mensagens ficam centralizadas em `app/src/main/res/values/strings.xml`. A máquina de estados é testada sem microfone ou GPS reais; os serviços Android permanecem separados da interface. Não há framework de injeção de dependência nesta versão.

Ao sair da tela, o aplicativo cancela a escuta, interrompe a fala e cancela a operação de localização pendente. Ao voltar, repete a instrução necessária e aguarda um toque para escutar. Rotações podem preservar os dados pelo ViewModel, mas não retomam a gravação. Ao liberar o ViewModel, os recursos de reconhecimento e síntese são fechados. Callbacks antigos de reconhecimento são descartados para não alterar uma nova tentativa.

## Privacidade e limites

- Latitude, longitude, precisão, horário, origem e destino ficam somente na memória da sessão. Encerrar a instância da atividade/ViewModel ou perder o processo descarta esses dados; ir à tela inicial do celular não necessariamente encerra a sessão.
- Preferências locais guardam apenas indicadores de que as permissões já foram solicitadas, para distinguir o primeiro pedido de um possível bloqueio. Não guardam a conversa nem coordenadas.
- O aplicativo não salva gravações, não implementa envio para servidores próprios e não inclui backend, analytics, API keys ou banco de dados. O backup do aplicativo está desabilitado.
- O reconhecimento no dispositivo é preferido quando disponível. O reconhecedor padrão recebe a preferência por uso offline, mas o provedor pode ignorá-la e processar áudio remotamente. O próprio serviço de voz pode precisar de rede e segue suas configurações e políticas; o protótipo **não promete funcionamento totalmente offline**.
- A disponibilidade de localização depende do Google Play services. Precisão, tempo de resposta, qualidade da voz, reconhecimento pt-BR, interação com TalkBack e vibração dependem do aparelho e precisam de testes físicos.
- A vibração respeita as configurações do dispositivo e complementa a comunicação por voz. Ela não é o único meio de informar um resultado.

## Próximas versões

O planejamento futuro inclui mapear um bloco da UNAERP, cadastrar salas, representar os pisos táteis como grafo, registrar pontos e caminhos acessíveis, implementar busca de rota com A*, fornecer instruções de percurso por voz, definir padrões de vibração para curvas e alertas e identificar o perímetro da universidade. Nada disso é simulado ou implementado nesta primeira versão.

## Trabalho em equipe

A branch padrão do repositório é **`main`**. A branch de trabalho é **`Desenvolvimento-Alexandre`**. Alexandre autorizou commits e pushes nessa branch de trabalho sempre que necessários. Merges, integração com o trabalho do colega e alterações/publicação na `main` ficam sob decisão dos desenvolvedores e exigem sua orientação. As regras locais estão em [AGENTS.md](AGENTS.md).

## Referências técnicas

As escolhas de versão e integração seguem a documentação oficial: [compatibilidade do AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [compatibilidade do plugin Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [acessibilidade dos componentes Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [permissões de localização](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime), [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) e [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech).
