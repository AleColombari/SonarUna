# SonarUna — roteiro de testes manuais

**Situação inicial: a executar.** Este documento é um roteiro, não uma declaração de aprovação. Registre evidências após cada execução. Build, testes unitários e testes Compose não comprovam, sozinhos, a experiência com TalkBack e os serviços de voz de um celular real.

O SonarUna é um protótipo acadêmico da UNAERP de Ribeirão Preto. Os testes não devem depender de deslocamento guiado pelo aplicativo: esta versão não fornece rotas e não substitui bengala, cão-guia, orientação humana ou outras ferramentas de mobilidade.

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
- [ ] TalkBack identifica o controle como botão e informa sua ação atual: origem, confirmação da origem, destino, confirmação do destino, nova tentativa ou reinício.
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
| Interface e TalkBack | A executar | — |
| Permissões e configurações | A executar | — |
| Localização | A executar | — |
| Conversa e correções | A executar | — |
| Erros de reconhecimento | A executar | — |
| Síntese e vibração | A executar | — |
| Ciclo de vida e privacidade | A executar | — |
