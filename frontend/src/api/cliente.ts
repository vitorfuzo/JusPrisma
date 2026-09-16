/**
 * Cliente HTTP da API.
 *
 * Duas decisões moram aqui e as duas são consequência direta do desenho do backend.
 *
 * 1. O token de acesso fica em memória, não em localStorage. Ele dura 15 minutos e
 *    qualquer script injetado na página leria o localStorage. O preço é que recarregar a
 *    página perde o token — resolvido renovando na inicialização, usando o cookie
 *    httpOnly que o JavaScript não enxerga.
 *
 * 2. A renovação é single-flight. Isso não é otimização: o backend rotaciona o token de
 *    renovação e trata reapresentação de um token já consumido como roubo, revogando a
 *    família inteira. Se três requisições recebessem 401 ao mesmo tempo e cada uma
 *    disparasse sua renovação, a segunda e a terceira usariam um token já gasto e o
 *    usuário seria deslogado de tudo — por um comportamento normal do próprio aplicativo.
 */

let tokenDeAcesso: string | null = null
let renovacaoEmAndamento: Promise<boolean> | null = null

type AoPerderSessao = () => void
let aoPerderSessao: AoPerderSessao = () => {}

export function definirTokenDeAcesso(token: string | null): void {
  tokenDeAcesso = token
}

export function tokenAtual(): string | null {
  return tokenDeAcesso
}

export function observarPerdaDeSessao(callback: AoPerderSessao): void {
  aoPerderSessao = callback
}

export class ErroDaApi extends Error {
  // Campos declarados e atribuidos separadamente: o template usa erasableSyntaxOnly, que
  // proibe propriedades de parametro no construtor por elas nao serem apagaveis na
  // transpilacao para JavaScript puro.
  readonly status: number
  readonly titulo: string
  readonly detalhe: string
  readonly correlacaoId?: string
  readonly campos?: Record<string, string>

  constructor(
    status: number,
    titulo: string,
    detalhe: string,
    correlacaoId?: string,
    campos?: Record<string, string>,
  ) {
    super(detalhe || titulo)
    this.status = status
    this.titulo = titulo
    this.detalhe = detalhe
    this.correlacaoId = correlacaoId
    this.campos = campos
  }
}

interface ProblemDetail {
  title?: string
  detail?: string
  correlacaoId?: string
  campos?: Record<string, string>
}

async function paraErro(resposta: Response): Promise<ErroDaApi> {
  let corpo: ProblemDetail = {}
  try {
    corpo = (await resposta.json()) as ProblemDetail
  } catch {
    // Resposta sem corpo JSON: 502 de proxy, timeout, etc.
  }
  return new ErroDaApi(
    resposta.status,
    corpo.title ?? 'Erro',
    corpo.detail ?? 'Não foi possível concluir a operação.',
    corpo.correlacaoId,
    corpo.campos,
  )
}

/**
 * Renova a sessão, garantindo que só uma renovação aconteça por vez.
 *
 * Chamadas concorrentes esperam a mesma promessa em vez de abrir a sua — ver o comentário
 * no topo do arquivo sobre por que isso é obrigatório.
 */
export async function renovarSessao(): Promise<boolean> {
  if (renovacaoEmAndamento) {
    return renovacaoEmAndamento
  }

  renovacaoEmAndamento = (async () => {
    try {
      const resposta = await fetch('/api/v1/sessoes/renovacao', {
        method: 'POST',
        credentials: 'include',
      })
      if (!resposta.ok) {
        tokenDeAcesso = null
        return false
      }
      const corpo = (await resposta.json()) as { tokenDeAcesso: string }
      tokenDeAcesso = corpo.tokenDeAcesso
      return true
    } catch {
      tokenDeAcesso = null
      return false
    } finally {
      renovacaoEmAndamento = null
    }
  })()

  return renovacaoEmAndamento
}

interface Opcoes {
  metodo?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  corpo?: unknown
  /** Rotas públicas não tentam renovar ao receber 401 — não há sessão para renovar. */
  publica?: boolean
}

export async function chamar<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  const { metodo = 'GET', corpo, publica = false } = opcoes

  const executar = async (): Promise<Response> => {
    const cabecalhos: Record<string, string> = {}
    if (corpo !== undefined) {
      cabecalhos['Content-Type'] = 'application/json'
    }
    if (tokenDeAcesso && !publica) {
      cabecalhos.Authorization = `Bearer ${tokenDeAcesso}`
    }

    return fetch(`/api${caminho}`, {
      method: metodo,
      headers: cabecalhos,
      body: corpo === undefined ? undefined : JSON.stringify(corpo),
      credentials: 'include',
    })
  }

  let resposta = await executar()

  // 401 numa rota autenticada quase sempre significa token expirado, não senha errada.
  // Tenta renovar uma única vez; se a renovação falhar, a sessão acabou de verdade.
  if (resposta.status === 401 && !publica) {
    const renovou = await renovarSessao()
    if (!renovou) {
      aoPerderSessao()
      throw await paraErro(resposta)
    }
    resposta = await executar()
  }

  if (!resposta.ok) {
    throw await paraErro(resposta)
  }

  if (resposta.status === 204) {
    return undefined as T
  }
  return (await resposta.json()) as T
}
