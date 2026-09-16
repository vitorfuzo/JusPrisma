import type { ReactNode } from 'react'

interface Props {
  titulo: string
  subtitulo?: string
  children: ReactNode
}

/** Moldura das telas públicas: entrar, criar conta, recuperar senha, aceitar convite. */
export function Moldura({ titulo, subtitulo, children }: Props) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <p className="text-2xl font-semibold tracking-tight text-marca-900">JusPrisma</p>
        </div>

        <main className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
          <h1 className="text-lg font-semibold text-slate-900">{titulo}</h1>
          {subtitulo && <p className="mb-5 mt-1 text-sm text-slate-500">{subtitulo}</p>}
          {children}
        </main>

        {/* Regra inviolável 6 do CLAUDE.md. Fica no rodapé de toda tela pública porque a
            primeira impressão do produto já precisa dizer o que ele é e o que não é. */}
        <p className="mt-6 text-center text-xs leading-relaxed text-slate-400">
          Ferramenta de apoio à atividade advocatícia. Não substitui o juízo profissional
          e não garante resultado.
        </p>
      </div>
    </div>
  )
}
