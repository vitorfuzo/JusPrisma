import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { chamar, ErroDaApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'

interface Convite {
  id: string
  email: string
  papel: 'OWNER' | 'MEMBRO'
  expiraEm: string
}

export function Equipe() {
  const fila = useQueryClient()
  const [email, setEmail] = useState('')
  const [erro, setErro] = useState<ErroDaApi | null>(null)

  const convites = useQuery({
    queryKey: ['convites'],
    queryFn: () => chamar<Convite[]>('/v1/convites'),
  })

  const convidar = useMutation({
    mutationFn: (destinatario: string) =>
      chamar<Convite>('/v1/convites', {
        metodo: 'POST',
        corpo: { email: destinatario, papel: 'MEMBRO' },
      }),
    onSuccess: () => {
      setEmail('')
      setErro(null)
      fila.invalidateQueries({ queryKey: ['convites'] })
    },
    onError: (e) => setErro(e as ErroDaApi),
  })

  const revogar = useMutation({
    mutationFn: (id: string) => chamar<void>(`/v1/convites/${id}`, { metodo: 'DELETE' }),
    onSuccess: () => fila.invalidateQueries({ queryKey: ['convites'] }),
  })

  return (
    <div className="flex flex-col gap-8">
      <section>
        <h1 className="text-xl font-semibold text-slate-900">Equipe</h1>
        <p className="mt-1 text-sm text-slate-500">
          Convide pessoas para trabalhar no escritório. Quem aceita passa a ver os
          processos e clientes desta conta.
        </p>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            convidar.mutate(email)
          }}
          className="flex flex-col gap-3 sm:flex-row sm:items-end"
        >
          <div className="flex-1">
            <Campo
              rotulo="E-mail de quem você quer convidar"
              name="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <Botao type="submit" carregando={convidar.isPending}>
            Enviar convite
          </Botao>
        </form>

        {erro && (
          <div className="mt-3">
            <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
              {/* 402 é limite de plano, não falta de permissão. A diferença importa: aqui
                  a ação certa é fazer upgrade, e dizer "sem permissão" mandaria a pessoa
                  procurar o administrador — que é ela mesma. */}
              {erro.status === 402
                ? `${erro.detalhe} Veja os planos disponíveis em Plano e créditos.`
                : erro.detalhe}
            </Aviso>
          </div>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Convites pendentes
        </h2>

        {convites.isLoading && <p className="text-slate-500">Carregando…</p>}

        {convites.data?.length === 0 && (
          <p className="text-sm text-slate-500">Nenhum convite aberto.</p>
        )}

        {convites.data && convites.data.length > 0 && (
          <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
            {convites.data.map((convite) => (
              <li
                key={convite.id}
                className="flex items-center justify-between gap-4 px-4 py-3"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-slate-900">
                    {convite.email}
                  </p>
                  <p className="text-xs text-slate-500">
                    expira em{' '}
                    {new Date(convite.expiraEm).toLocaleDateString('pt-BR', {
                      day: '2-digit',
                      month: 'long',
                    })}
                  </p>
                </div>
                <button
                  onClick={() => revogar.mutate(convite.id)}
                  disabled={revogar.isPending}
                  className="shrink-0 text-sm text-red-700 hover:underline disabled:opacity-50"
                >
                  Revogar
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
