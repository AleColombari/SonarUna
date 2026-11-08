# SonarUna — roteiro de testes manuais

**Situação inicial: a executar.** Este documento é um roteiro, não uma declaração de aprovação. Registre evidências após cada execução. Build, testes unitários e testes Compose não comprovam, sozinhos, a experiência com TalkBack e os serviços de voz de um celular real.

O SonarUna é um protótipo acadêmico da UNAERP de Ribeirão Preto. Ele não substitui bengala, cão-guia, orientação humana ou outras ferramentas de mobilidade. Nos testes de rota no campus, a pessoa que testa deve estar acompanhada e não depender só do aplicativo.

**Versão 0.2.0.** A seção 0 cobre o que mudou: mapa, rotas, GPS e microfone que abre sozinho depois de cada pergunta. As seções 1 a 7 foram escritas para a 0.1.0. Onde elas dizem que o microfone nunca abre sozinho, que é preciso tocar antes de cada resposta ou que não há rota, vale a seção 0.

## 0. Versão 0.2.0: orientação pelo mapa

Abertura e permissões

- [ ] Ao abrir, o app fala "Toque no botão, no centro da tela, para começar a orientação" e o microfone continua desligado.
- [ ] No primeiro toque de uma instalação limpa, o app explica os acessos e mostra os pedidos do microfone e da localização.
- [ ] Negar só a localização não impede a orientação: o app pergunta "Onde você está agora?".
- [ ] Negar o microfone explica que ele é necessário. Repetir as negativas até o bloqueio faz o botão abrir as configurações.

GPS (ao ar livre, no campus, com localização **precisa**)

- [ ] Na entrada principal, o app fala só "Procurando sua localização." e, ficando em silêncio, depois pergunta "Parece que você está na entrada principal. Está certo?".
- [ ] Logo depois de "Procurando sua localização.", dizer "estou na sala 5A": o app aceita na hora e não faz a pergunta do GPS depois.
- [ ] Ao abrir, dizer "estou na 3A e quero ir para a 10B": o app diz "Da sala 3A para a sala 10B, em 8 etapas." e já fala a primeira etapa.
- [ ] Falar a frase com uma pausa no meio ("estou na 3A ... e quero ir para a 10B"): mesmo que o reconhecedor corte, a segunda parte vira o destino e a rota começa.
- [ ] Dizer "10B" várias vezes, falando por cima ou depois da voz: anotar o que aparece escrito embaixo do botão. Se aparecer outra sala ("3B"), dizer "bloco B, sala 10" e anotar se acerta.
- [ ] A voz fala cada resposta de uma vez, sem pausas entre as frases, um pouco mais rápida que a voz padrão do aparelho.
- [ ] Ao abrir, dizer só "quero ir para a 10B": o app pergunta onde a pessoa está e, com "estou na 3A", já fala a rota.
- [ ] Começar uma frase longa bem quando o GPS deve responder: o app não corta a frase no meio.
- [ ] Repetir na entrada do bloco B e na entrada do bloco A.
- [ ] "Sim" confirma. "Não" pergunta onde a pessoa está. "Não, estou no balcão de informações" corrige direto.
- [ ] Com a localização só aproximada, ou dentro de um prédio, o app não sugere lugar e só pergunta onde a pessoa está.
- [ ] Fora do campus, o app avisa que a pessoa não parece estar na UNAERP.

Microfone e interrupção

- [ ] Depois de cada pergunta, o microfone abre sozinho: vibração curta e descrição "Ouvindo. Pode falar".
- [ ] Tocar enquanto o app fala interrompe a fala e já escuta.
- [ ] **Eco:** no alto-falante, sem fone, deixar o app falar até o fim sem dizer nada, várias vezes (na rota e nas perguntas). Ele não pode avançar etapa nem responder à própria voz; o texto embaixo do botão não deve mostrar frases do app.
- [ ] **Falar por cima:** começar a responder enquanto o app ainda faz a pergunta. A voz do app para e a resposta vale. Testar com o alto-falante e com fone de ouvido, e registrar se o app interrompe a si mesmo (eco) ou se o reconhecedor faz um bipe a cada tentativa.
- [ ] Tocar enquanto o app escuta pausa a orientação ("Escuta pausada"). Um novo toque retoma onde parou.
- [ ] Numa pergunta, ficar em silêncio três vezes seguidas fecha o microfone ("Não ouvi nenhuma resposta"). Um toque retoma a mesma pergunta.
- [ ] Na rota, andar a etapa inteira sem falar: o microfone fecha ("Microfone pausado") e a rota continua guardada. Um toque retoma na mesma etapa, e "pronto" passa à próxima.
- [ ] Nenhuma fala do próprio app, nem do TalkBack, é reconhecida como resposta. Registrar se isso acontecer.

Destino e rota

- [ ] Destinos falados de vários jeitos: "sala 15B", "quinze b", "bê quinze", "financeiro", "LIAPE", "sala dos computadores".
- [ ] "Banheiro" leva ao mais perto, sem confirmação. "Não, feminino" no começo da rota troca para o feminino mais perto. "Sim" no caminho não faz o app falar.
- [ ] "Sala 9" pede o nome completo (há 9A e 9B). "Trinta a" responde que a sala A30 não existe no mapa.
- [ ] A rota fala quantas etapas tem e a primeira etapa.
  - "Pronto" passa à próxima, "voltar" repete a anterior e "repetir" repete a atual.
  - Na última etapa, "pronto" diz "Você chegou".
- [ ] **No campus, acompanhado:** seguir a rota da entrada principal até a sala 20B e das Informações até a sala 15B. Registrar:
  - se cada "cruzamento" contado bate com o piso real;
  - se o lado das curvas está certo;
  - se a escada desce do bloco B para o A;
  - se as referências ajudam.
- [ ] "Preciso de ajuda" informa que a ligação ainda não está no app e repete a pergunta atual.
- [ ] Na rota, "quanto falta?", "repasse o caminho" e "onde estou?" respondem e mantêm a etapa atual.
- [ ] As etapas falam só do piso ("Daqui a 3 cruzamentos, vire à esquerda"). Conferir no campus se cada contagem bate com os cruzamentos sentidos com a bengala.
- [ ] "Parar" encerra; "recomeçar" volta ao início.
- [ ] "Não" ou "outro destino" no meio da rota pergunta só o destino. A nova rota parte do fim da última etapa feita.
- [ ] Dentro do bloco B, "saída" leva à porta do bloco B. Dentro do bloco A, leva à porta do bloco A. "Quero sair" faz o mesmo e não encerra a orientação.

## Registro da execução

| Informação | Preencher |
| --- | --- |
| Data e responsável | A executar |
| Commit e branch | A executar |
| Aparelho / emulador | A executar |
| Versão do Android | A executar |
| Versão do TalkBack | A executar |
| Serviço de síntese e voz pt-BR | A executar |
| Serviço de reconhecimento e modelo pt-BR local | A executar |
| Versão do Google Play services | A executar |
| Conectividade / localização ativada | A executar |
| Resultado geral e problemas encontrados | A executar |

Use um aparelho Android 8.0 ou superior com Google Play services. Instale o APK de debug, habilite a síntese pt-BR e confirme que TalkBack e volume de acessibilidade estão funcionando. Para testar o primeiro acesso, use uma instalação limpa ou limpe os dados do aplicativo nas configurações do Android; isso remove os dados locais desse aplicativo e reinicia o histórico de pedidos de permissão.

Os caminhos dos menus e os textos dos diálogos de permissão variam entre versões e fabricantes. Teste pelo menos um Android 12 ou superior para cobrir a opção de localização aproximada e os controles globais do microfone.

## 1. Tela e TalkBack

- [ ] A tela do aplicativo tem fundo branco e somente um botão circular preto com ícone de microfone branco, centralizado e grande. Barras e diálogos do sistema são separados da interface do aplicativo.
- [ ] Ao navegar por gestos do TalkBack dentro do conteúdo do aplicativo, existe apenas um alvo de ação. O ícone interno não aparece como um segundo elemento.
- [ ] TalkBack identifica o controle como botão e informa sua ação atual: origem, confirmação da origem, destino, nova tentativa ou reinício.
- [ ] A ativação normal por TalkBack executa a mesma ação do toque direto. Também testar a execução com TalkBack desligado.
- [ ] Durante a escuta, a descrição muda para “Ouvindo. Toque para cancelar”, o estado informa microfone ativo e o botão continua acionável.
- [ ] Acionar o botão durante a escuta cancela a captura. A mensagem de cancelamento é falada e um resultado atrasado não muda a etapa.
- [ ] Durante uma instrução falada ou busca da localização, a descrição explica a espera. O botão fica temporariamente indisponível e volta a funcionar ao término.
- [ ] O foco permanece previsível ao trocar de etapa; não é necessário procurar outro controle.
- [ ] Retrato, paisagem, tamanho de fonte grande, tamanho de exibição ampliado e ampliação de acessibilidade mantêm o botão utilizável, sem corte ou sobreposição.
- [ ] Não é necessário interpretar uma cor, ler coordenadas ou enxergar uma animação para concluir o fluxo.
- [ ] Verificar se TalkBack e as instruções do aplicativo ficam inteligíveis no aparelho testado. Registrar qualquer fala sobreposta, repetida ou cortada; a interação entre os mecanismos precisa de avaliação real.

## 2. Primeiro acesso e permissões

- [ ] Abrir uma instalação limpa. A explicação em português do Brasil termina antes de os pedidos de acesso aparecerem.
- [ ] O microfone não começa a capturar na inicialização, ao aceitar permissões nem ao concluir a pergunta de origem. Conferir também o indicador de privacidade do Android, quando disponível.
- [ ] Autorizar microfone e localização “Durante o uso do app” permite prosseguir.
- [ ] No Android 12+, selecionar **Aproximada** e negar a precisa não impede o fluxo. O aplicativo não insiste em obter precisão exata.
- [ ] Quando disponível, testar “Somente desta vez”. Sair e retornar após a revogação não causa falha nem acesso sem permissão.
- [ ] Negar somente microfone produz explicação falada e possibilidade de nova tentativa pelo botão.
- [ ] Negar somente localização produz explicação falada e possibilidade de nova tentativa pelo botão.
- [ ] Negar ambos mantém a recuperação acessível, sem iniciar o reconhecedor.
- [ ] Negar definitivamente, ou repetir negativas até o Android bloquear novos pedidos. O aplicativo explica a necessidade de liberar o acesso nas configurações.
- [ ] Nesse estado, o botão abre a página do SonarUna nas configurações. Liberar os acessos e retornar permite continuar.
- [ ] Retornar das configurações sem liberar o acesso mantém a explicação e a ação de recuperação.
- [ ] Revogar uma permissão nas configurações durante uma sessão e voltar. O aplicativo reavalia os acessos e não inicia uma captura automaticamente.
- [ ] Nenhum diálogo solicita localização em segundo plano. Vibração não apresenta pedido runtime.

## 3. Localização aproximada

- [ ] Com localização ativada e permissões concedidas, ouvir “Localização aproximada obtida” e a pergunta de origem. Não aparecem coordenadas na tela.
- [ ] Confirmar pelo depurador, sem publicar dados pessoais, que a sessão contém latitude, longitude, precisão e horário da leitura.
- [ ] Desativar a localização do aparelho antes da tentativa. A mensagem identifica a configuração desativada e orienta habilitá-la.
- [ ] Habilitar a localização, voltar e usar o botão para tentar novamente. Não é necessário reiniciar o aplicativo.
- [ ] Testar onde não há leitura disponível. A tentativa termina após aproximadamente 20 segundos, apresenta erro falado e permite nova tentativa, sem espera infinita.
- [ ] Em aparelho ou emulador de teste sem Google Play services disponível, o aplicativo informa a dependência e permite tentar novamente. Não deve encerrar inesperadamente.
- [ ] Em ambiente interno, nenhuma mensagem afirma identificar a sala nem trata GPS como posição exata.

## 4. Conversa completa e correções

- [ ] Depois da pergunta de origem, aguardar sem tocar: não há captura automática.
- [ ] Acionar o botão, dizer “Estou na sala 12” e aguardar. O aplicativo repete o conteúdo reconhecido e pergunta se está correto.
- [ ] Acionar novamente e dizer “sim”. O aplicativo confirma a origem e pede o destino.
- [ ] Acionar e dizer “Biblioteca”. O aplicativo repete o destino e solicita confirmação.
- [ ] Acionar e dizer “confirmo”. Ouvir origem e destino confirmados e a informação explícita de que o cálculo da rota ainda não está disponível.
- [ ] O botão de conclusão oferece “Iniciar uma nova orientação”. Não começa qualquer orientação de percurso.
- [ ] Repetir a confirmação da origem com “não”, “errado”, “não está correto” e “corrigir”. Cada expressão retorna à pergunta de origem.
- [ ] Repetir a confirmação do destino com uma negativa. Apenas o destino é solicitado novamente; a origem confirmada é preservada.
- [ ] Testar “correto”, “isso” e “está certo” como respostas positivas.
- [ ] Dizer uma resposta desconhecida, como “talvez”, ou contraditória, como “sim, não”. O aplicativo pede apenas sim ou não e mantém a etapa.
- [ ] Informar lugares com acentos e várias palavras. A repetição preserva o conteúdo da melhor hipótese recebida, sujeito à qualidade do serviço de reconhecimento.
- [ ] Não tocar enquanto a instrução é reproduzida. O botão só permite responder quando a fala termina; nenhum áudio do próprio aplicativo é reconhecido como resposta.

## 5. Silêncio, rede e disponibilidade do microfone

- [ ] Iniciar a escuta e ficar em silêncio. O aplicativo encerra a tentativa, informa que não entendeu e permite tentar novamente.
- [ ] Testar áudio não compreendido ou resultado vazio. A etapa atual é preservada.
- [ ] Fazer várias tentativas e cancelamentos consecutivos. Nenhum resultado de uma tentativa antiga confirma outra resposta.
- [ ] Com reconhecimento local pt-BR disponível, testar sem rede e registrar se o serviço local funciona.
- [ ] Sem modelo local disponível, interromper a rede em um ambiente que utilize reconhecimento remoto. Se o provedor retornar erro de conexão, ouvir a mensagem correspondente e conseguir tentar novamente após restabelecer a rede.
- [ ] Desabilitar o serviço de reconhecimento em um aparelho de teste, ou usar um dispositivo sem ele. O aplicativo informa indisponibilidade e mantém uma ação de nova tentativa.
- [ ] Testar modelo pt-BR ausente ou idioma não suportado. Ouvir a explicação sobre o idioma e conseguir tentar novamente depois da configuração.
- [ ] Desativar globalmente o acesso ao microfone no Android, quando disponível, ou usar uma situação de microfone ocupado. O erro é comunicado e não causa encerramento inesperado.
- [ ] Reativar o acesso ao microfone e tentar novamente. A escuta só começa depois da ação explícita no botão.
- [ ] Depois de resultado, erro ou cancelamento, conferir que o aplicativo não mantém o microfone ativo. Alguns indicadores do Android permanecem visíveis por um breve período após o uso; verificar também o histórico de privacidade.

## 6. Síntese de voz e vibração

- [ ] As mensagens são faladas em português do Brasil, inclusive quando o idioma geral do aparelho é diferente.
- [ ] A fala não muda silenciosamente para inglês quando pt-BR está ausente.
- [ ] Em aparelho de teste, remover ou desabilitar a voz pt-BR. A descrição do botão explica o problema e oferece nova tentativa.
- [ ] Com TalkBack funcional, verificar o anúncio acessível de falha da síntese. Se o próprio TalkBack depender do mesmo mecanismo ausente, registrar que não há garantia de anúncio falado.
- [ ] Instalar/habilitar a voz pt-BR, voltar ao aplicativo e tentar novamente. O fluxo normal deve poder começar.
- [ ] Confirmar uma vibração curta ao iniciar a escuta, duas curtas ao reconhecer uma resposta e uma mais longa em erro.
- [ ] Desligar o feedback tátil nas configurações do aparelho. O aplicativo respeita a preferência e continua compreensível pela voz.
- [ ] Em dispositivo sem vibrador, o restante do fluxo continua funcionando.

## 7. Segundo plano, rotação e sessão

- [ ] Durante a escuta, pressionar Home ou abrir outro aplicativo. O microfone é encerrado.
- [ ] Voltar ao SonarUna. A instrução necessária é repetida, mas o microfone não reabre até uma ativação explícita.
- [ ] Durante a síntese, sair para Home. A fala é interrompida e pode ser retomada como instrução ao retornar.
- [ ] Durante a obtenção da localização, sair da tela. A operação pendente é cancelada; ao voltar, o aplicativo continua de forma compreensível.
- [ ] Girar o dispositivo em cada etapa, inclusive durante escuta e confirmação. A sessão pode ser preservada pelo ViewModel, mas a captura não deve continuar nem reiniciar sozinha.
- [ ] Após concluir uma conversa rapidamente, iniciar outra enquanto a leitura tem até dois minutos. Origem e destino anteriores são apagados e a localização pode ser reutilizada.
- [ ] Concluir uma conversa e iniciar outra quando a leitura tiver mais de dois minutos. O aplicativo busca localização novamente antes de perguntar a origem.
- [ ] Usar o gesto/botão Voltar e abrir novamente. O microfone deve parar. A sessão pode permanecer se o Android apenas enviar a atividade principal ao segundo plano; caso a atividade e seu ViewModel sejam encerrados, a sessão seguinte deve estar vazia.
- [ ] Forçar a parada do aplicativo nas configurações e reabrir. Os dados da conversa anterior não reaparecem. Permissões concedidas pelo Android podem permanecer, assim como os indicadores locais de pedidos anteriores.
- [ ] Inspecionar o armazenamento de depuração, sem registrar dados pessoais. As preferências locais contêm apenas o histórico de solicitação das permissões, sem coordenadas, transcrições, locais ou gravações.

## Registro de problemas

Para cada falha, registrar: etapa, ação realizada, resultado esperado, resultado observado, modelo do aparelho, Android, serviço de voz e configurações relevantes. Anexar somente evidências que não exponham localização pessoal ou gravações/transcrições de terceiros. Marcar o caso como aprovado apenas depois de repeti-lo com a correção aplicada.

| Caso | Resultado / evidência | Responsável / data |
| --- | --- | --- |
| 0.2.0: GPS, microfone, destino e rota | A executar | — |
| Interface e TalkBack | A executar | — |
| Permissões e configurações | A executar | — |
| Localização | A executar | — |
| Conversa e correções | A executar | — |
| Erros de reconhecimento | A executar | — |
| Síntese e vibração | A executar | — |
| Ciclo de vida e privacidade | A executar | — |
