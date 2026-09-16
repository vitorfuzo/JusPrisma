import { useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { chamar } from '../api/cliente'
import { useAutenticacao } from '../auth/useAutenticacao'

const abas = [
  { para: '/', rotulo: 'Início', exato: true },
  { para: '/planos', rotulo: 'Plano e créditos' },
  { para: '/equipe', rotulo: 'Equipe', somenteDono: true },
]

export function MolduraInterna() {
  const { sessao, sair } = useAutenticacao()
  const navegar = useNavigate()
  const [reenviando, setReenviando] = useState(false)
  const [reenviado, setReenviado] = useState(false)

  async function encerrar() {
    await sair()
    navegar('/entrar', { replace: true })
  }

  async function reenviarVerificacao() {
    setReenviando(true)
    try {
      await chamar('/v1/contas/verificacao/reenvio', { metodo: 'POST' })
      setReenviado(true)
    } finally {
      setReenviando(false)
    }
  }

  const visiveis = abas.filter((aba) => !aba.somenteDono || sessao?.papel === 'OWNER')

  return (
    <div className="min-h-dvh bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3">
          <Link to="/" className="text-lg font-semibold tracking-tight text-marca-900">
            JusPrisma
          </Link>
          <nav className="flex flex-1 gap-1">
            {visiveis.map((aba) => (
              <NavLink
                key={aba.para}
                to={aba.para}
                end={aba.exato}
                className={({ isActive }) =>
                  `rounded-md px-3 py-1.5 text-sm transition ${
                    isActive
                      ? 'bg-marca-50 font-medium text-marca-700'
                      : 'text-slate-600 hover:bg-slate-100'
                  }`
                }
              >
                {aba.rotulo}
              </NavLink>
            ))}
          </nav>
          <button
            onClick={encerrar}
            className="rounded-md px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-100"
          >
            Sair
          </button>
        </div>
      </header>

      {/* Aviso de e-mail não verificado. Fica no topo e não bloqueia: travar a plataforma
          inteira por causa de um e-mail não confirmado perde o usuário que acabou de
          pagar. O que a verificação protege é o envio de notificação, não o acesso. */}
      {sessao && !sessao.emailVerificado && (
        <div className="border-b border-amber-200 bg-amber-50">
          <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-3 px-4 py-2 text-sm text-amber-900">
            <span>Confirme seu e-mail para receber alertas e notificações.</span>
            {reenviado ? (
              <span className="font-medium">Link reenviado.</span>
            ) : (
              <button
                onClick={reenviarVerificacao}
                disabled={reenviando}
                className="font-medium underline disabled:opacity-50"
              >
                {reenviando ? 'Enviando…' : 'Reenviar link'}
              </button>
            )}
          </div>
        </div>
      )}

      <main className="mx-auto max-w-5xl px-4 py-8">
        <Outlet />
      </main>

      <footer className="mx-auto max-w-5xl px-4 pb-10">
        {/* Regra inviolável 6 do CLAUDE.md. */}
        <p className="border-t border-slate-200 pt-4 text-xs leading-relaxed text-slate-400">
          Ferramenta de apoio à atividade advocatícia. Não substitui o juízo profissional
          e não garante resultado.
        </p>
      </footer>
    </div>
  )
}
