import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ErroDaApi } from '../api/cliente'
import { useAutenticacao } from '../auth/useAutenticacao'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'
import { Moldura } from './Moldura'

export function Entrar() {
  const { entrar } = useAutenticacao()
  const navegar = useNavigate()
  const local = useLocation()

  const [email, setEmail] = useState('')
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await entrar(email, senha)
      const destino = (local.state as { de?: string } | null)?.de ?? '/'
      navegar(destino, { replace: true })
    } catch (e) {
      setErro(e as ErroDaApi)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Moldura titulo="Entrar" subtitulo="Acesse sua conta do JusPrisma">
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        {erro && (
          <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
            {/* 429 tem tratamento próprio: o texto genérico não explica por que a senha
                certa deixou de funcionar, e a pessoa fica tentando de novo. */}
            {erro.status === 429
              ? 'Tentativas demais. Aguarde alguns minutos antes de tentar novamente.'
              : erro.detalhe}
          </Aviso>
        )}

        <Campo
          rotulo="E-mail"
          name="email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Campo
          rotulo="Senha"
          name="senha"
          type="password"
          autoComplete="current-password"
          required
          value={senha}
          onChange={(e) => setSenha(e.target.value)}
        />

        <Botao type="submit" carregando={enviando}>
          Entrar
        </Botao>

        <div className="flex justify-between text-sm">
          <Link to="/recuperar-senha" className="text-marca-700 hover:underline">
            Esqueci minha senha
          </Link>
          <Link to="/criar-conta" className="text-marca-700 hover:underline">
            Criar conta
          </Link>
        </div>
      </form>
    </Moldura>
  )
}
