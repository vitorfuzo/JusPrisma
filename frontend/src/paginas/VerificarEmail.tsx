import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { chamar, ErroDaApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Moldura } from './Moldura'

type Estado = 'conferindo' | 'confirmado' | 'falhou'

export function VerificarEmail() {
  const [parametros] = useSearchParams()
  const token = parametros.get('token') ?? ''
  // Estado inicial derivado do token em vez de ajustado dentro do efeito: sem token
  // nao ha o que conferir, e chamar setState sincronamente num efeito dispara uma
  // renderizacao extra a toa.
  const [estado, setEstado] = useState<Estado>(token ? 'conferindo' : 'falhou')
  const [erro, setErro] = useState<ErroDaApi | null>(null)

  /*
   * O token é de uso único, e isso tem uma consequência específica aqui: em
   * desenvolvimento o React monta o componente duas vezes no modo estrito. Sem esta
   * trava, a segunda montagem reenviaria o token que a primeira acabou de consumir, o
   * servidor responderia 410 corretamente, e a pessoa veria "link inválido" logo depois
   * de ele ter funcionado.
   */
  const jaEnviou = useRef(false)

  useEffect(() => {
    if (!token || jaEnviou.current) return
    jaEnviou.current = true

    chamar('/v1/contas/verificacao', { metodo: 'POST', publica: true, corpo: { token } })
      .then(() => setEstado('confirmado'))
      .catch((e) => {
        setErro(e as ErroDaApi)
        setEstado('falhou')
      })
  }, [token])

  return (
    <Moldura titulo="Verificação de e-mail">
      {estado === 'conferindo' && <p className="text-slate-500">Conferindo o link…</p>}

      {estado === 'confirmado' && (
        <>
          <Aviso tipo="sucesso">E-mail confirmado. Sua conta está ativa.</Aviso>
          <p className="mt-4 text-center text-sm">
            <Link to="/entrar" className="text-marca-700 hover:underline">
              Ir para o login
            </Link>
          </p>
        </>
      )}

      {estado === 'falhou' && (
        <Aviso tipo="erro" correlacaoId={erro?.correlacaoId}>
          {erro?.detalhe ?? 'Este link é inválido ou expirou.'} Entre na sua conta e peça
          o reenvio.
        </Aviso>
      )}
    </Moldura>
  )
}
