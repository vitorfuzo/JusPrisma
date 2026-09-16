import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { chamar, ErroDaApi } from '../api/cliente'
import { useAutenticacao } from '../auth/useAutenticacao'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'
import { Moldura } from './Moldura'

const TAMANHO_MINIMO_DA_SENHA = 10

export function CriarConta() {
  const { entrar } = useAutenticacao()
  const navegar = useNavigate()

  const [form, setForm] = useState({
    nomeDoEscritorio: '',
    email: '',
    senha: '',
    oab: '',
    ufOab: '',
  })
  const [erro, setErro] = useState<ErroDaApi | null>(null)
  const [enviando, setEnviando] = useState(false)

  function alterar(campo: keyof typeof form) {
    return (e: React.ChangeEvent<HTMLInputElement>) =>
      setForm((atual) => ({ ...atual, [campo]: e.target.value }))
  }

  async function enviar(evento: React.FormEvent) {
    evento.preventDefault()
    setErro(null)
    setEnviando(true)
    try {
      await chamar('/v1/contas', {
        metodo: 'POST',
        publica: true,
        corpo: {
          nomeDoEscritorio: form.nomeDoEscritorio,
          email: form.email,
          senha: form.senha,
          // Campos vazios viram ausentes: o backend valida formato quando presentes, e
          // string vazia falharia a validação de UF sem que a pessoa tenha preenchido nada.
          oab: form.oab || undefined,
          ufOab: form.ufOab || undefined,
        },
      })

      // Entra direto após criar. Pedir para digitar a mesma senha de novo, dois segundos
      // depois, é atrito sem ganho — a pessoa acabou de provar que a conhece.
      await entrar(form.email, form.senha)
      navegar('/', { replace: true })
    } catch (e) {
      setErro(e as ErroDaApi)
    } finally {
      setEnviando(false)
    }
  }

  const senhaCurta = form.senha.length > 0 && form.senha.length < TAMANHO_MINIMO_DA_SENHA

  return (
    <Moldura titulo="Criar conta" subtitulo="14 dias de degustação para conhecer a plataforma">
      <form onSubmit={enviar} className="flex flex-col gap-4" noValidate>
        {erro && (
          <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
            {erro.detalhe}
          </Aviso>
        )}

        <Campo
          rotulo="Nome do escritório"
          name="nomeDoEscritorio"
          required
          value={form.nomeDoEscritorio}
          onChange={alterar('nomeDoEscritorio')}
          erro={erro?.campos?.nomeDoEscritorio}
        />
        <Campo
          rotulo="E-mail"
          name="email"
          type="email"
          autoComplete="email"
          required
          value={form.email}
          onChange={alterar('email')}
          erro={erro?.campos?.email}
        />
        <Campo
          rotulo="Senha"
          name="senha"
          type="password"
          autoComplete="new-password"
          required
          minLength={TAMANHO_MINIMO_DA_SENHA}
          value={form.senha}
          onChange={alterar('senha')}
          erro={
            senhaCurta
              ? `Use ao menos ${TAMANHO_MINIMO_DA_SENHA} caracteres`
              : erro?.campos?.senha
          }
        />

        <div className="grid grid-cols-[1fr_6rem] gap-3">
          <Campo
            rotulo="OAB (opcional)"
            name="oab"
            value={form.oab}
            onChange={alterar('oab')}
            erro={erro?.campos?.oab}
          />
          <Campo
            rotulo="UF"
            name="ufOab"
            maxLength={2}
            value={form.ufOab}
            onChange={(e) =>
              setForm((atual) => ({ ...atual, ufOab: e.target.value.toUpperCase() }))
            }
            erro={erro?.campos?.ufOab}
          />
        </div>

        <Botao type="submit" carregando={enviando} disabled={senhaCurta}>
          Criar conta
        </Botao>

        <p className="text-center text-sm text-slate-500">
          Já tem conta?{' '}
          <Link to="/entrar" className="text-marca-700 hover:underline">
            Entrar
          </Link>
        </p>
      </form>
    </Moldura>
  )
}
