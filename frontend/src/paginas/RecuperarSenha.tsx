import { useState } from 'react'
import { Link } from 'react-router-dom'
import { chamar, ErroDaApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'
import { Moldura } from './Moldura'

export function RecuperarSenha() {
  const [email, setEmail] = useState('')
  const [enviado, setEnviado] = useState(false)
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await chamar('/v1/senha/recuperacao', { metodo: 'POST', publica: true, corpo: { email } })
      setEnviado(true)
    } catch (e) {
      setErro(e as ErroDaApi)
    } finally {
      setEnviando(false)
    }
  }

  if (enviado) {
    return (
      <Moldura titulo="Verifique seu e-mail">
        {/* Mensagem deliberadamente igual exista ou não a conta. Dizer "não encontramos
            esse e-mail" transformaria esta tela num verificador de quem é cliente. O
            backend responde igual nos dois casos, e a interface precisa acompanhar —
            senão o vazamento que o servidor evitou volta pela porta da frente. */}
        <Aviso tipo="sucesso">
          Se houver uma conta para <strong>{email}</strong>, enviamos um link de
          redefinição. Ele vale por uma hora e só pode ser usado uma vez.
        </Aviso>
        <p className="mt-4 text-center text-sm">
          <Link to="/entrar" className="text-marca-700 hover:underline">
            Voltar para o login
          </Link>
        </p>
      </Moldura>
    )
  }

  return (
    <Moldura titulo="Recuperar senha" subtitulo="Enviaremos um link para o seu e-mail">
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        {erro && (
          <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
            {erro.status === 429
              ? 'Você já pediu o link algumas vezes. Aguarde antes de pedir de novo.'
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
        <Botao type="submit" carregando={enviando}>
          Enviar link
        </Botao>
        <p className="text-center text-sm">
          <Link to="/entrar" className="text-marca-700 hover:underline">
            Voltar para o login
          </Link>
        </p>
      </form>
    </Moldura>
  )
}
