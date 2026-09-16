import { createContext } from 'react'

export type Papel = 'OWNER' | 'MEMBRO'

export interface Sessao {
  usuarioId: string
  tenantId: string
  papel: Papel
  emailVerificado: boolean
}

export interface ValorDaAutenticacao {
  sessao: Sessao | null
  carregando: boolean
  entrar: (email: string, senha: string) => Promise<void>
  sair: () => Promise<void>
  atualizarSessao: () => void
}

/*
 * O contexto e os tipos vivem num arquivo separado do componente de propósito: quando um
 * arquivo exporta componentes e não-componentes ao mesmo tempo, o fast refresh do Vite
 * deixa de funcionar e cada alteração recarrega a página inteira, perdendo o estado.
 */
// Nome em maiuscula porque e' usado como componente no JSX: identificador em
// minuscula seria interpretado como elemento HTML nativo.
export const ContextoAutenticacao = createContext<ValorDaAutenticacao | null>(null)
