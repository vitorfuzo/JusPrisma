import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { chamar, ErroDaApi } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'
import { Botao } from '../componentes/Botao'
import { Campo } from '../componentes/Campo'

interface PlanoContratavel {
  codigo: string
  /** Nome e preço já formatados por quem chama. */
  rotulo: string
}

interface Contratacao {
  planoCodigo: string
  documento: string
  primeiraCobranca: string
}

/**
 * Aplica a máscara de CPF até 11 dígitos e a de CNPJ a partir do 12º. O servidor aceita
 * com ou sem máscara; ela existe só para a pessoa conferir o que digitou.
 */
function mascararDocumento(valor: string): string {
  const d = valor.replace(/\D/g, '').slice(0, 14)
  if (d.length <= 11) {
    return d
      .replace(/^(\d{3})(\d)/, '$1.$2')
      .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
      .replace(/\.(\d{3})(\d{1,2})$/, '.$1-$2')
  }
  return d
    .replace(/^(\d{2})(\d)/, '$1.$2')
    .replace(/^(\d{2})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d)/, '.$1/$2')
    .replace(/(\d{4})(\d{1,2})$/, '$1-$2')
}

export function FormularioDeContratacao({ planos }: { planos: PlanoContratavel[] }) {
  const fila = useQueryClient()
  const [plano, setPlano] = useState(planos[0]?.codigo ?? '')
  const [documento, setDocumento] = useState('')
  const [erro, setErro] = useState<ErroDaApi | null>(null)

  const contratar = useMutation({
    mutationFn: () =>
      chamar<Contratacao>('/v1/assinatura/contratacao', {
        metodo: 'POST',
        corpo: { planoCodigo: plano, documento },
      }),
    onSuccess: () => {
      setErro(null)
      setDocumento('')
      fila.invalidateQueries({ queryKey: ['assinatura'] })
    },
    onError: (e) => setErro(e as ErroDaApi),
  })

  if (planos.length === 0) {
    return null
  }

  return (
    <form
      data-testid="formulario-contratacao"
      onSubmit={(e) => {
        e.preventDefault()
        contratar.mutate()
      }}
      className="flex flex-col gap-4 rounded-lg border border-slate-200 bg-white p-4"
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-sm font-medium text-slate-700">Plano</legend>
        {planos.map((p) => (
          <label key={p.codigo} className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="radio"
              name="plano"
              value={p.codigo}
              checked={plano === p.codigo}
              onChange={() => setPlano(p.codigo)}
            />
            {p.rotulo}
          </label>
        ))}
      </fieldset>

      <div className="sm:max-w-xs">
        <Campo
          rotulo="CPF ou CNPJ de quem paga"
          name="documento"
          inputMode="numeric"
          autoComplete="off"
          required
          value={documento}
          onChange={(e) => setDocumento(mascararDocumento(e.target.value))}
          erro={erro?.campos?.documento}
        />
        <p className="mt-1 text-xs text-slate-500">
          Usado apenas para emitir a cobrança.
        </p>
      </div>

      <div>
        <Botao type="submit" carregando={contratar.isPending}>
          Contratar
        </Botao>
      </div>

      {erro && (
        <Aviso tipo="erro" titulo={erro.titulo} correlacaoId={erro.correlacaoId}>
          {erro.detalhe}
        </Aviso>
      )}
    </form>
  )
}
