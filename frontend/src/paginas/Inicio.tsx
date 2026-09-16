import { Link } from 'react-router-dom'
import { useAutenticacao } from '../auth/useAutenticacao'

export function Inicio() {
  const { sessao } = useAutenticacao()

  return (
    <div className="flex flex-col gap-8">
      <section>
        <h1 className="text-xl font-semibold text-slate-900">Início</h1>
        <p className="mt-1 text-sm text-slate-500">
          {sessao?.papel === 'OWNER'
            ? 'Você administra este escritório.'
            : 'Você é membro deste escritório.'}
        </p>
      </section>

      {/* O Perfil de Magistrado é a Fase 1. Anunciar o que vem, em vez de deixar a tela
          vazia, evita que a primeira impressão do produto seja um espaço em branco. */}
      <section className="rounded-lg border border-dashed border-slate-300 bg-white p-6">
        <h2 className="font-semibold text-slate-900">Perfil de Magistrado</h2>
        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-slate-600">
          Em breve você poderá pesquisar um relator ou órgão julgador e receber um
          relatório de como aquele julgador decide — com estatística descritiva sobre
          decisões públicas e cada afirmação ligada à decisão que a sustenta.
        </p>
      </section>

      <section className="flex flex-wrap gap-3">
        <Link
          to="/planos"
          className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
        >
          Ver plano e créditos
        </Link>
        {sessao?.papel === 'OWNER' && (
          <Link
            to="/equipe"
            className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50"
          >
            Convidar alguém
          </Link>
        )}
      </section>
    </div>
  )
}
