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
5. A cada passo, diga "pronto" para o próximo. Você também pode dizer "repetir", "voltar", "recomeçar", "ajuda" ou "parar".

Se o microfone não funcionar, use o campo de texto da página: ele passa pela mesma conversa.

## Testes automáticos

```bash
node --test web/test/engine.test.mjs web/test/conversation.test.mjs
```

## Arquivos

| Arquivo | O que faz |
| --- | --- |
| `../data/bloco-a.json` | Mapa do bloco A: entradas, pontos e trechos. **Hoje são dados fictícios.** |
| `engine.js` | Reconhece sala e entrada ("a quinze" → A15), calcula a rota e escreve as instruções. |
| `conversation.js` | A conversa: entrada → destino → confirmação → passos. Não depende do navegador. |
| `app.js`, `index.html`, `style.css` | Interface, microfone e voz. |
| `serve.mjs` | Servidor local sem dependências. |

## Trocar os dados fictícios pelos reais

Edite `data/bloco-a.json` seguindo o roteiro em [docs/MAPEAMENTO.md](../docs/MAPEAMENTO.md). Cada trecho tem `metros` e `rumo`, a direção da caminhada em graus pela bússola: 0 = norte, 90 = leste, 180 = sul, 270 = oeste. O app calcula sozinho o lado da curva ("vire à direita") comparando um rumo com o seguinte, e inverte quando o caminho é feito na volta.

Se o arquivo tiver erro (ponto que não existe, trecho com 0 metros), o carregamento falha com uma mensagem clara.

## Limites desta versão

- O mapa é fictício e cobre só o térreo.
- O app não sabe onde a pessoa está: ela confirma cada passo com "pronto".
- Sem equipamento no prédio, a distância em passos é aproximada.
- Não substitui bengala, cão-guia nem ajuda humana.
