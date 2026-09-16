import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { chamar, ErroDaApi } from '../api/cliente'
import { useAutenticacao } from '../auth/useAutenticacao'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'
import { Moldura } from './Moldura'

const TAMANHO_MINIMO_DA_SENHA = 10

interface ConvitePendente {
  nomeDoEscritorio: string
  email: string
  papel: 'OWNER' | 'MEMBRO'
}

export function AceitarConvite() {
  const [parametros] = useSearchParams()
  const navegar = useNavigate()
  const { entrar } = useAutenticacao()
  const token = parametros.get('token') ?? ''

  const [convite, setConvite] = useState<ConvitePendente | null>(null)
  const [senha, setSenha] = useState('')
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  // Sem token nao ha busca a fazer, entao ja nasce fora do estado de carregamento.
  const [carregando, setCarregando] = useState(Boolean(token))
  const [enviando, setEnviando] = useState(false)

  // Busca o convite antes de mostrar o formulário. Aceitar um convite é passar a enxergar
  // processos e clientes de um escritório; a pessoa precisa ver de qual antes de decidir.
  useEffect(() => {
    if (!token) return
    chamar<ConvitePendente>(`/v1/convites/pendente?token=${encodeURIComponent(token)}`, {
      publica: true,
    })
      .then(setConvite)
      .catch((e) => setErro(e as ErroDaApi))
      .finally(() => setCarregando(false))
  }, [token])

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await chamar('/v1/convites/aceite', {
        metodo: 'POST',
        publica: true,
        corpo: { token, senha },
      })
      if (convite) {
        await entrar(convite.email, senha)
        navegar('/', { replace: true })
      }
    } catch (e) {
      setErro(e as ErroDaApi)
    } finally {
      setEnviando(false)
    }
  }

  if (carregando) {
    return (
      <Moldura titulo="Convite">
        <p className="text-slate-500">Conferindo o convite…</p>
      </Moldura>
    )
  }

  if (!convite) {
    return (
      <Moldura titulo="Convite inválido">
        <Aviso tipo="erro" correlacaoId={erro?.correlacaoId}>
          {erro?.detalhe ?? 'Este convite é inválido, expirou ou já foi usado.'} Peça um
          novo a quem o convidou.
        </Aviso>
        <p className="mt-4 text-center text-sm">
          <Link to="/entrar" className="text-marca-700 hover:underline">
            Ir para o login
          </Link>
        </p>
      </Moldura>
    )
  }

  const curta = senha.length > 0 && senha.length < TAMANHO_MINIMO_DA_SENHA

  return (
    <Moldura titulo="Você foi convidado" subtitulo="Crie sua senha para entrar">
      <div className="mb-5 rounded-md border border-slate-200 bg-slate-50 px-4 py-3 text-sm">
        <p className="text-slate-500">Escritório</p>
        <p className="font-semibold text-slate-900">{convite.nomeDoEscritorio}</p>
        <p className="mt-2 text-slate-500">Seu acesso</p>
        <p className="text-slate-900">
          {convite.email} · {convite.papel === 'OWNER' ? 'Administrador' : 'Membro'}
        </p>
      </div>

      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        {erro && (
          <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
            {erro.detalhe}
          </Aviso>
        )}
        <Campo
          rotulo="Crie sua senha"
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
          Aceitar convite
        </Botao>
      </form>
    </Moldura>
  )
}
