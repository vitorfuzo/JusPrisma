interface Props {
  tipo?: 'erro' | 'sucesso' | 'informacao'
  titulo?: string
  children: React.ReactNode
  correlacaoId?: string
}

export function Aviso({ tipo = 'informacao', titulo, children, correlacaoId }: Props) {
  const estilo = {
    erro: 'border-red-200 bg-red-50 text-red-900',
    sucesso: 'border-emerald-200 bg-emerald-50 text-emerald-900',
    informacao: 'border-slate-200 bg-slate-50 text-slate-800',
  }[tipo]

  return (
    <div
      role={tipo === 'erro' ? 'alert' : 'status'}
      className={`rounded-md border px-4 py-3 text-sm ${estilo}`}
    >
      {titulo && <p className="font-semibold">{titulo}</p>}
      <div>{children}</div>
      {correlacaoId && (
        // Exibir o identificador permite que a pessoa o cite no suporte, e é o que liga a
        // reclamacao a linha de log exata.
        <p className="mt-2 font-mono text-xs opacity-70">Código: {correlacaoId}</p>
      )}
    </div>
  )
}
