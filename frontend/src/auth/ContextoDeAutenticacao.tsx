import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  chamar,
  definirTokenDeAcesso,
  observarPerdaDeSessao,
  renovarSessao,
  tokenAtual,
} from '../api/cliente'
import { ContextoAutenticacao, type Sessao, type ValorDaAutenticacao } from './contexto'

interface RespostaDeSessao {
  tokenDeAcesso: string
  emailVerificado: boolean
}

/** Lê as informações da sessão do próprio token, sem uma chamada extra ao servidor. */
function lerSessaoDoToken(token: string, emailVerificado: boolean): Sessao | null {
  try {
    const carga = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return {
      usuarioId: carga.sub,
      tenantId: carga.tenant,
      papel: carga.papel,
      emailVerificado,
    }
  } catch {
    // Token malformado é tratado como ausência de sessão. A validação de verdade é do
    // servidor; aqui só lemos o que já foi assinado por ele.
    return null
  }
}

export function ProvedorDeAutenticacao({ children }: { children: ReactNode }) {
  const [sessao, setSessao] = useState<Sessao | null>(null)
  const [carregando, setCarregando] = useState(true)

  const encerrarLocalmente = useCallback(() => {
    definirTokenDeAcesso(null)
    setSessao(null)
  }, [])

  useEffect(() => {
    observarPerdaDeSessao(encerrarLocalmente)
  }, [encerrarLocalmente])

  // Na inicialização, tenta renovar. O token de acesso vive em memória e se perde ao
  // recarregar a página; o cookie de renovação sobrevive, então quem tinha sessão válida
  // continua logado sem digitar a senha de novo.
  useEffect(() => {
    let cancelado = false

    renovarSessao()
      .then((renovou) => {
        if (cancelado) return
        const token = tokenAtual()
        if (renovou && token) {
          setSessao(lerSessaoDoToken(token, true))
        }
      })
      .finally(() => {
        if (!cancelado) setCarregando(false)
      })

    return () => {
      cancelado = true
    }
  }, [])

  const entrar = useCallback(async (email: string, senha: string) => {
    const resposta = await chamar<RespostaDeSessao>('/v1/sessoes', {
      metodo: 'POST',
      corpo: { email, senha },
      publica: true,
    })
    definirTokenDeAcesso(resposta.tokenDeAcesso)
    setSessao(lerSessaoDoToken(resposta.tokenDeAcesso, resposta.emailVerificado))
  }, [])

  const sair = useCallback(async () => {
    try {
      await chamar<void>('/v1/sessoes', { metodo: 'DELETE', publica: true })
    } finally {
      // O estado local é limpo mesmo se a chamada falhar: um logout que parece ter
      // funcionado mas deixou a sessão aberta é pior que um erro visível.
      encerrarLocalmente()
    }
  }, [encerrarLocalmente])

  const atualizarSessao = useCallback(() => {
    const token = tokenAtual()
    if (token) setSessao(lerSessaoDoToken(token, true))
  }, [])

  const valor = useMemo<ValorDaAutenticacao>(
    () => ({ sessao, carregando, entrar, sair, atualizarSessao }),
    [sessao, carregando, entrar, sair, atualizarSessao],
  )

  return <ContextoAutenticacao value={valor}>{children}</ContextoAutenticacao>
}
