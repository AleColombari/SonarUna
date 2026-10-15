# Bancada web do SonarUna (localhost)

Versão web de teste do SonarUna. Ela ouve pelo microfone, fala com a voz do navegador e guia por um mapa do bloco A. Serve para testar a conversa e as rotas rapidamente no computador, antes de levar a mesma lógica para o app Android.

## Como rodar

Precisa de Node 20 ou mais novo e do **Google Chrome** (ou Edge), porque o reconhecimento de voz depende do navegador.

```bash
node web/serve.mjs
```

Abra <http://localhost:8080/> e permita o microfone. O Chrome envia o áudio ao serviço de voz do Google, então precisa de internet para reconhecer a fala. A voz que fala com você é a do sistema, instalada no Windows (Configurações → Hora e idioma → Fala).

## Roteiro de teste

1. Toque no botão. O app pergunta por qual entrada você entrou.
2. Diga "entrada principal" ou "entrada lateral".
3. Diga o destino: "sala A12", "a doze", "secretaria", "banheiro".
4. Confirme com "sim" (ou "não" para trocar).
5. A cada etapa, diga "pronto" para a próxima. Você também pode dizer "repetir", "voltar", "recomeçar", "ajuda" ou "parar".

Se o microfone não funcionar, use o campo de texto da página: ele passa pela mesma conversa.

## Testes automáticos

```bash
node --test web/test/engine.test.mjs web/test/conversation.test.mjs web/test/contacts.test.mjs
```

## Arquivos

| Arquivo | O que faz |
| --- | --- |
| `../data/bloco-a.json` | Mapa do bloco A: entradas, pontos e trechos. **Hoje são dados fictícios.** |
| `engine.js` | Reconhece sala e entrada ("a quinze" → A15), calcula a rota e escreve as instruções. |
| `conversation.js` | A conversa: entrada → destino → confirmação → passos. Não depende do navegador. |
| `app.js`, `index.html`, `style.css` | Interface, microfone e voz. |
| `contacts.js` | Telefones de ajuda: validação, armazenamento local e modo de ligação (real ou simulada). |
| `serve.mjs` | Servidor local sem dependências. Também lê o `.env` e entrega os telefones em `/config.json`. |

## Trocar os dados fictícios pelos reais

Edite `data/bloco-a.json` seguindo o roteiro em [docs/MAPEAMENTO.md](../docs/MAPEAMENTO.md). Cada trecho tem `metros`, `rumo` (a direção da caminhada em graus pela bússola: 0 = norte, 90 = leste, 180 = sul, 270 = oeste) e, opcionalmente, `passos` contados caminhando. O app **fala em passos**: usa os `passos` do trecho quando existem e, senão, converte os metros com `passoMetros` (0,7 m por passo, a média de um adulto; veja a tabela em [docs/MAPEAMENTO.md](../docs/MAPEAMENTO.md)). O app calcula sozinho o lado da curva ("vire à direita") comparando um rumo com o seguinte, e inverte quando o caminho é feito na volta.

Se o arquivo tiver erro (ponto que não existe, trecho com 0 metros), o carregamento falha com uma mensagem clara.

## Pedido de ajuda por ligação

A qualquer momento a pessoa pode dizer **"preciso de ajuda"** (ou "socorro", "me ajuda", "estou perdido"). O app pergunta com quem ela quer falar, **atendente** ou **segurança**, e liga para o telefone cadastrado. Se só um telefone estiver cadastrado, o app pergunta "Você quer falar com o atendente? Diga sim ou não". Dizer "cancelar" volta para onde a conversa estava. Depois da ligação, tocar no botão retoma a rota na mesma etapa.

**Onde ficam os telefones.** Nunca no repositório. Há duas formas:

1. **Arquivo `.env`** (o Git ignora). Copie `.env.example` para `.env` e preencha `ATENDENTE_TELEFONE` e `SEGURANCA_TELEFONE`. O servidor lê o arquivo a cada acesso e entrega os números à página em `/config.json`. Se um papel vem do `.env`, ele vale mais que o digitado na página.
2. **Formulário "Contatos de ajuda"** na própria página. Os números ficam só no navegador de quem digitou (`localStorage`). É o caminho para um site publicado sem servidor, ou para digitar direto no celular.

Mesmo com o repositório privado, prefira o `.env`: um número commitado fica no histórico do Git para sempre.

**No computador a ligação é simulada.** A tela mostra "SIMULAÇÃO: no celular o site ligaria agora para segurança (final 0111)". **No celular** aparece um botão grande "Ligar para …" e a página tenta abri-lo sozinha; ele usa o link `tel:`, que abre o discador do aparelho. A página só mostra o final do número, nunca ele inteiro, e a voz nunca o lê. Para forçar um modo nos testes, use `?ligacao=real` ou `?ligacao=simulada` na URL.

**Limite importante.** Navegadores costumam exigir um toque da pessoa para abrir o discador, e ainda não testamos isso num celular. Por isso o botão grande "Ligar para …" fica na tela como reserva. Uma ligação 100% automática só existe em app Android nativo, com a permissão `CALL_PHONE`.

Isto **não substitui os serviços de emergência**: o atendente e a segurança são contatos da faculdade. Em risco de vida, a pessoa deve ligar para o SAMU (192) ou a polícia (190).

## Testar no celular

O microfone e o reconhecimento de voz só funcionam em páginas **HTTPS** (ou `localhost`). Abrir o endereço do computador pela rede local (`http://192.168...`) não habilita o microfone no celular. Para testar lá, o site precisa estar publicado em HTTPS ou aberto por um túnel HTTPS. Ainda não escolhemos onde publicar, e nada disso foi testado num celular.

## Limites desta versão

- O mapa é fictício e cobre só o térreo.
- O app não sabe onde a pessoa está: ela confirma cada etapa com "pronto".
- Passos variam de pessoa para pessoa. O app diz "cerca de" quando a conta quebra e arredonda de 5 em 5 acima de 10 passos.
- Não substitui bengala, cão-guia nem ajuda humana.
