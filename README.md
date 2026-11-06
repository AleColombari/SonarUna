# SonarUna

Protótipo acadêmico Android para um projeto da **UNAERP, em Ribeirão Preto**: um guia por voz para pessoas com deficiência visual, que orienta pelo piso tátil do campus. A pessoa diz onde está e para onde quer ir, e o app fala o caminho em etapas, só pelo piso: "Daqui a 3 cruzamentos, vire à esquerda".

**O aplicativo não substitui bengala, cão-guia, orientação humana ou outras ferramentas de mobilidade, e nunca afirma que um caminho é seguro.**

O repositório tem duas versões com o mesmo comportamento e os mesmos dados:

- o **app Android** (`app/`, Kotlin), descrito aqui;
- a **versão web** de testes (`web/`), que roda no computador pelo Chrome: veja [web/README.md](web/README.md).

## O que esta versão faz (0.2.0)

- **Tela:** branca, com um único botão circular preto, grande e centralizado, com ícone de microfone. Funciona com TalkBack e vibra ao começar a ouvir, ao entender e em erro.
- **Conversa:**
  1. Ao abrir, sem toque e sem apresentação, o app diz só "Procurando sua localização." e já escuta enquanto o GPS lê.
     - Se a pessoa disser onde está antes do GPS responder ("estou na 3A"), vale o que ela disse e a leitura é ignorada.
     - Se o GPS responder primeiro e a pessoa estiver perto de uma entrada, o app pergunta "Parece que você está na entrada principal. Está certo?". Sem sugestão, pergunta "Onde você está agora?".
  2. A pessoa diz o destino: "sala 15B", "quinze b", "financeiro", "banheiro" (o mais perto), "LIAPE". O app confirma ("Você quer ir para a sala 15B?") e fala a rota em etapas. A pessoa diz "pronto" para ouvir a próxima.
  3. **Tudo numa frase:** "estou na 3A e quero ir para a 10B" (ou "quero ir pra 10B, estou na 3A", "sim, quero ir para o financeiro" depois da pergunta do GPS) já faz o caminho, sem confirmar. Se a pessoa disser só o destino antes de dizer onde está, o app guarda o destino, pergunta onde ela está e então sai direto para a rota.
- **Mapa:** o da UNAERP, guiado pelo piso tátil e sem medidas: [docs/MAPA-UNAERP.md](docs/MAPA-UNAERP.md). Cada etapa conta os cruzamentos até a próxima virada ("No próximo cruzamento, vire à direita"). As salas não servem de referência no caminho, só aparecem como destino. Uma reta longa vira etapas de até 5 cruzamentos, e escadas, portas e referências físicas ("porta sempre aberta") também são faladas.
- **Respostas:** sim e não são entendidos dentro de frases ("acho que sim", "não, me enganei"). Na confirmação, dizer outro lugar já troca o destino.
- **Comandos a qualquer momento:** repetir, voltar, recomeçar, ajuda e parar. Na rota, também: "quanto falta?", "repasse o caminho" (as etapas que faltam) e "onde estou?".
- **Trocar o destino sem recomeçar:** "outro destino" ou "nova rota" pergunta só o destino. No meio da rota, dizer um lugar também troca o destino, e a nova rota parte do fim da última etapa feita.
- **Saída:** "saída" ou "quero sair" leva à saída mais perto (entrada principal, porta do bloco B ou porta do bloco A).
- **Microfone:** quando o app espera resposta, o microfone já escuta enquanto ele fala.
  - A pessoa pode **responder por cima**, falando: a voz do app para e a resposta vale. Palavras que o próprio app já disse são tratadas como eco do alto-falante, e por isso funciona melhor com fone de ouvido.
  - Tocar no botão enquanto o app fala também interrompe e já escuta. Tocar enquanto ele escuta não corta o microfone.
  - O microfone não fecha sozinho: numa pergunta, o app pede para repetir até duas vezes e depois só escuta; na rota, o silêncio é a pessoa andando e o app escuta calado até ela dizer "pronto". Para encerrar, a pessoa diz "parar".

**Ainda não estão no app Android:** o pedido de ajuda por ligação (existe só na versão web) e os caminhos marcados como futuros no mapa (bloco H, pavimentos superiores).

## Requisitos e tecnologias

Aplicativo: `SonarUna`, versão `0.2.0`. Namespace e identificador Android: `com.example.sonaruna`.

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
| Mapa | `org.json` do Android; nos testes JVM, `org.json:json` 20240303 |

O aparelho precisa de microfone, de um serviço de reconhecimento de voz e de síntese de voz em português do Brasil. Para a sugestão por GPS, precisa também de Google Play services e da localização **precisa** ativada. A disponibilidade de reconhecimento local e de modelos de idioma varia por aparelho e provedor.

Recomenda-se **testar em celular físico com TalkBack**. Um emulador ajuda a verificar a interface, mas não comprova a experiência real de voz, vibração, localização ou leitor de tela.

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

Os testes unitários (`app/src/test/`) leem os mesmos `data/unaerp.json` e `data/unaerp-pontos.geojson` do app e conferem as **mesmas frases** esperadas nos testes da versão web:

- rotas reais do campus, com escadas, portas, referências e divisão em etapas;
- nenhuma etapa com mais de 5 cruzamentos, entre todos os pares de lugares;
- reconhecimento de salas e serviços falados;
- sim e não dentro de frases;
- sugestão por GPS nas entradas;
- a conversa completa, com silêncio, comandos e troca de destino.

Os testes Compose (`app/src/androidTest/`) verificam:

- que existe um único botão com nome acessível;
- que dá para pausar a escuta;
- que dá para interromper a fala;
- que o botão espera enquanto o app busca a localização.

### Estado das verificações

| Verificação | Situação |
| --- | --- |
| Build de debug, testes unitários e Lint | Aprovados localmente: 49 testes unitários, incluindo os 13 casos compartilhados com a web; Lint sem erros, com 14 avisos de versões de dependências e metadados de backup |
| Testes de interface | 4 compilados; execução em dispositivo/emulador a fazer |
| Conversa, mapa e rotas no celular com voz real | A executar |
| Sugestão por GPS nas entradas do campus | A executar |
| TalkBack, vibração e configurações de acessibilidade | A executar |

A validação de setembro (versão 0.1.0) está em [VALIDACAO.md](docs/VALIDACAO.md). Use o [roteiro de testes manuais](docs/TESTES_MANUAIS.md) e registre o aparelho, as versões e os resultados antes de considerar a experiência acessível validada.

O workflow [Android](.github/workflows/android.yml) repete o build, os testes unitários, o Lint, a compilação dos testes de interface e os testes da versão web em pushes na branch de trabalho e em pull requests. Ele disponibiliza o APK e os relatórios como artefato por 14 dias; não executa testes de voz ou TalkBack.

## Fluxo da conversa

1. **Abertura:** configura a voz pt-BR, carrega o mapa e começa sozinho, sem toque. Se faltar o microfone, explica por voz e mostra os pedidos de acesso do Android (microfone e localização juntos). A localização é pedida uma vez só e é opcional.
2. **Onde você está:** com a localização permitida, o app diz só "Procurando sua localização." e já escuta enquanto faz uma leitura de até 8 segundos. O que a pessoa disser antes vale mais que o GPS; se ela estiver no meio de uma frase quando o GPS responder, o app espera a frase acabar.
   - Perto de uma entrada marcada no mapa, ele pergunta se a pessoa está ali.
   - Fora do campus, ele avisa antes de perguntar.
   - Sem leitura boa (erro maior que 10 m), ele só pergunta onde a pessoa está.
3. **Destino e confirmação:** a pessoa diz o destino; "sim" confirma, "não" pergunta de novo, e dizer outro lugar já troca o destino. Origem e destino na mesma frase ("estou na 3A e quero ir para a 10B") pulam a confirmação.
4. **Rota:** o app fala o resumo e a primeira etapa. "Pronto" passa para a próxima, "voltar" repete a anterior, "repetir" repete a atual. Ao chegar, o destino vira o novo ponto de partida e o app já escuta o próximo destino; "parar" encerra.

As falas da conversa ficam em [guia/Conversation.kt](app/src/main/java/com/example/sonaruna/guia/Conversation.kt), com o mesmo texto da versão web. As mensagens do sistema (permissões, erros, descrições do botão) ficam em `app/src/main/res/values/strings.xml`.

## Permissões e recuperação

| Permissão | Uso |
| --- | --- |
| `RECORD_AUDIO` | Obrigatória: ouvir as respostas |
| `ACCESS_FINE_LOCATION` | Opcional: uma leitura precisa em primeiro plano, para sugerir em qual entrada a pessoa está |
| `ACCESS_COARSE_LOCATION` | Pedida junto com a precisa. Sozinha, tem precisão de cerca de 3 km², e o app não sugere lugar |
| `VIBRATE` | Feedback tátil; permissão normal, sem diálogo em tempo de execução |

Não há acesso à localização em segundo plano. Sem permissão de localização, com a localização desligada ou sem Google Play services, a conversa segue normalmente e só pergunta onde a pessoa está.

Uma negativa do microfone recebe explicação por voz e permite nova solicitação. Quando o Android não permite apresentar o pedido novamente, o botão abre as configurações do aplicativo.

Microfone indisponível, falha de rede do reconhecedor, idioma ausente ou mapa que não carrega recebem mensagens faladas, e o botão tenta de novo.

Se a síntese pt-BR não puder iniciar, o aplicativo tenta anunciar o problema por acessibilidade para o TalkBack e mantém a instrução de recuperação na descrição do botão. **Esse recurso depende de um leitor de tela funcional: não garante comunicação falada quando nenhum mecanismo de voz do dispositivo funciona.**

## Organização e ciclo de vida

```text
app/src/main/java/com/example/sonaruna/
├── MainActivity.kt                 # Permissões, configurações e ciclo de vida da tela
├── MainScreen.kt                   # Um único botão Compose acessível
├── MainViewModel.kt                # Liga a conversa à voz, ao microfone e à localização
├── guia/                           # Kotlin puro, sem Android (porte de web/engine.js e web/conversation.js)
│   ├── FloorMap.kt                 # Lê o mapa, acha lugares falados e calcula a rota (Dijkstra)
│   ├── Instructions.kt             # Etapas pelo piso tátil: cruzamentos, escadas, portas, referências
│   ├── Answers.kt                  # Sim e não dentro de frases; comandos de voz
│   ├── GpsPoints.kt                # Pontos de GPS (GeoJSON) e sugestão do lugar de partida
│   ├── Conversation.kt             # A conversa inteira, com as falas
│   └── Text.kt                     # Normalização de texto (acentos, pontuação)
├── conversation/
│   └── RoomCodeParser.kt           # "30A", "trinta a", "bê doze" -> código da sala
└── platform/
    ├── LocationRepository.kt       # Uma leitura do FusedLocationProviderClient
    ├── SpeechRecognitionManager.kt # Uma resposta por vez, com as hipóteses do reconhecedor
    ├── TextToSpeechManager.kt      # Inicialização e conclusão das falas
    └── HapticFeedback.kt           # Padrões simples de vibração
```

O mapa e os pontos de GPS não são copiados para dentro do app: o Gradle empacota a pasta `data/` como *assets*. Corrigir [data/unaerp.json](data/unaerp.json) vale para o app e para a versão web.

Ao sair da tela, o aplicativo cancela a escuta, interrompe a fala e cancela a leitura de localização pendente. Ao voltar, o botão oferece "Continuar orientação", e um toque repete onde a conversa estava. Ao liberar o ViewModel, os recursos de reconhecimento e síntese são fechados. Callbacks antigos de reconhecimento são descartados para não alterar uma nova tentativa.

## Privacidade e limites

- **Dados da sessão:** coordenadas, origem, destino e rota ficam somente na memória. Encerrar a atividade ou perder o processo descarta tudo.
- **Preferências locais:** guardam apenas indicadores de que as permissões já foram pedidas. Não guardam a conversa nem coordenadas.
- **Sem servidor:** o aplicativo não salva gravações, não envia dados a servidores próprios e não tem backend, analytics, chaves de API nem banco de dados. O backup do aplicativo está desabilitado.
- **Reconhecimento de voz:** o reconhecimento no próprio aparelho é preferido quando existe. O provedor pode ignorar essa preferência e processar o áudio remotamente, então o protótipo **não promete funcionar sem internet**.
- **GPS:** dentro dos prédios o GPS não acha salas. Ele só sugere o ponto de partida ao ar livre, perto de uma entrada, e a pessoa sempre confirma.
- **Mapa:** foi feito de um esboço e de pontos marcados no Google My Maps. As dúvidas que ainda faltam conferir no campus estão em [docs/MAPA-UNAERP.md](docs/MAPA-UNAERP.md).

## Próximas versões

- Testar no celular, com TalkBack, e no campus, com quem usa bengala.
- Pedido de ajuda por ligação no app Android, como na versão web.
- Mapear os caminhos futuros: bloco H, continuação do bloco B e pavimentos superiores.
- Acrescentar referências nos trechos longos.

## Trabalho em equipe

A branch padrão do repositório é **`main`**. A branch de trabalho é **`Desenvolvimento-Alexandre`**. Alexandre autorizou commits e pushes nessa branch de trabalho sempre que necessários. Merges, integração com o trabalho do colega e alterações/publicação na `main` ficam sob decisão dos desenvolvedores e exigem sua orientação. As regras locais estão em [AGENTS.md](AGENTS.md).

## Referências técnicas

As escolhas de versão e integração seguem a documentação oficial: [compatibilidade do AGP 8.13](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [compatibilidade do plugin Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [acessibilidade dos componentes Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [permissões de localização](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime), [precisão da localização](https://developer.android.com/training/location/permissions), [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) e [TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech).
