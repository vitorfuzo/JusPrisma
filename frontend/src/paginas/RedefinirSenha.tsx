import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { chamar, ErroDaApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'
import { Moldura } from './Moldura'

const TAMANHO_MINIMO_DA_SENHA = 10

export function RedefinirSenha() {
  const [parametros] = useSearchParams()
  const navegar = useNavigate()
  const token = parametros.get('token') ?? ''

  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await chamar('/v1/senha/redefinicao', {
        metodo: 'POST',
        publica: true,
        corpo: { token, senha },
      })
      navegar('/entrar', {
        replace: true,
        state: { aviso: 'Senha alterada. Entre com a senha nova.' },
      })
    } catch (e) {
      setErro(e as ErroDaApi)
    } finally {
      setEnviando(false)
    }
  }

  if (!token) {
    return (
      <Moldura titulo="Link inválido">
        <Aviso tipo="erro">
          Este endereço não contém um link de redefinição válido. Peça um novo.
        </Aviso>
        <p className="mt-4 text-center text-sm">
          <Link to="/recuperar-senha" className="text-marca-700 hover:underline">
            Pedir novo link
          </Link>
        </p>
      </Moldura>
    )
  }

  const curta = senha.length > 0 && senha.length < TAMANHO_MINIMO_DA_SENHA

  return (
    <Moldura
      titulo="Criar senha nova"
      subtitulo="Ao concluir, todas as sessões abertas serão encerradas"
    >
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        {erro && (
          <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
            {erro.detalhe}
            {erro.status === 410 && (
              <>
                {' '}
                <Link to="/recuperar-senha" className="underline">
                  Pedir um novo link
                </Link>
              </>
            )}
          </Aviso>
        )}
        <Campo
          rotulo="Senha nova"
          name="senha"
          type="password"
          autoComplete="new-password"
          required
          minLength={TAMANHO_MINIMO_DA_SENHA}
          value={senha}
          onChange={(e) => setSenha(e.target.value)}
          erro={curta ? `Use ao menos ${TAMANHO_MINIMO_DA_SENHA} caracteres` : undefined}
        />
        <Botao type="submit" carregando={enviando} disabled={curta}>
          Salvar senha
        </Botao>
      </form>
    </Moldura>
  )
}
