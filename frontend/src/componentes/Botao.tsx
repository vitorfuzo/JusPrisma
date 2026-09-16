import type { ButtonHTMLAttributes } from 'react'

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  carregando?: boolean
  variante?: 'primario' | 'secundario'
}

export function Botao({ carregando, variante = 'primario', children, ...resto }: Props) {
  const estilo =
    variante === 'primario'
      ? 'bg-marca-600 text-white hover:bg-marca-700 disabled:bg-slate-300'
      : 'border border-slate-300 text-slate-700 hover:bg-slate-50 disabled:text-slate-400'

  return (
    <button
      // Desabilitar enquanto carrega evita o duplo clique — que aqui não é detalhe de
      // polimento: no disparo de perfil, dois cliques são duas tentativas de débito.
      disabled={carregando || resto.disabled}
      className={`rounded-md px-4 py-2 font-medium transition disabled:cursor-not-allowed ${estilo}`}
      {...resto}
    >
      {carregando ? 'Aguarde…' : children}
    </button>
  )
}
