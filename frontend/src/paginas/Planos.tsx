import { useQuery } from '@tanstack/react-query'
import { chamar } from '../api/cliente'
import { Aviso } from '../componentes/Aviso'

interface Cota {
  chave: string
  teto: number | null
  ilimitada: boolean
  descricao: string
}

interface Plano {
  codigo: string
  nome: string
  precoCentavos: number
  cotas: Cota[]
  recursos: string[]
}

interface Situacao {
  plano: Plano
  saldos: Record<string, number>
}

const NOME_DA_COTA: Record<string, string> = {
  PERFIS_NOVOS_MES: 'Perfis novos por mês',
  COMPARACAO_MAGISTRADOS: 'Comparar magistrados',
  IA_MENSAGENS_MES: 'Mensagens de IA por mês',
  CALCULOS: 'Cálculos',
  ASSINATURAS: 'Assinaturas eletrônicas',
  CONSULTAS: 'Consultas de dados',
  MONITORAMENTO_PROCESSOS: 'Processos monitorados',
  DRIVE_GB: 'Armazenamento (GB)',
  SUBUSUARIOS: 'Subusuários',
  ROLLOVER_MESES: 'Acúmulo de créditos (meses)',
}

const NOME_DO_RECURSO: Record<string, string> = {
  ALERTA_VIRADA_ENTENDIMENTO: 'Alerta de virada de entendimento',
  PDF_WHITE_LABEL: 'Relatório com a marca do escritório',
  API: 'Acesso à API',
}

const NOME_DO_CREDITO: Record<string, string> = {
  PERFIL: 'Perfis de magistrado',
  IA: 'IA jurídica',
  CALCULO: 'Cálculos',
  CONSULTA: 'Consultas',
  ASSINATURA: 'Assinaturas',
}

function reais(centavos: number): string {
  return (centavos / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

export function Planos() {
  const situacao = useQuery({
    queryKey: ['assinatura'],
    queryFn: () => chamar<Situacao>('/v1/assinatura'),
  })

  const catalogo = useQuery({
    queryKey: ['planos'],
    queryFn: () => chamar<Plano[]>('/v1/planos', { publica: true }),
  })

  if (situacao.isLoading || catalogo.isLoading) {
    return <p className="text-slate-500">Carregando…</p>
  }

  if (situacao.isError) {
    return (
      <Aviso tipo="erro" titulo="Não foi possível carregar sua assinatura">
        Recarregue a página. Se persistir, fale com o suporte.
      </Aviso>
    )
  }

  const atual = situacao.data!.plano

  return (
    <div className="flex flex-col gap-8">
      <section>
        <h1 className="text-xl font-semibold text-slate-900">Plano e créditos</h1>
        <p className="mt-1 text-sm text-slate-500">
          Seu plano atual é <strong>{atual.nome}</strong> ({reais(atual.precoCentavos)}).
        </p>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Saldo de créditos
        </h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {Object.entries(situacao.data!.saldos).map(([tipo, saldo]) => (
            <div
              key={tipo}
              className="rounded-lg border border-slate-200 bg-white px-4 py-3"
            >
              <p className="text-sm text-slate-500">{NOME_DO_CREDITO[tipo] ?? tipo}</p>
              <p className="text-2xl font-semibold text-slate-900">{saldo}</p>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          O que seu plano inclui
        </h2>
        <dl className="divide-y divide-slate-200 rounded-lg border border-slate-200 bg-white">
          {atual.cotas.map((cota) => (
            <div key={cota.chave} className="flex justify-between px-4 py-2.5 text-sm">
              <dt className="text-slate-600">{NOME_DA_COTA[cota.chave] ?? cota.chave}</dt>
              <dd className="font-medium text-slate-900">{cota.descricao}</dd>
            </div>
          ))}
          {atual.recursos.map((recurso) => (
            <div key={recurso} className="flex justify-between px-4 py-2.5 text-sm">
              <dt className="text-slate-600">{NOME_DO_RECURSO[recurso] ?? recurso}</dt>
              <dd className="font-medium text-emerald-700">incluído</dd>
            </div>
          ))}
        </dl>
      </section>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Outros planos
        </h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {catalogo.data!.map((plano) => (
            <div
              key={plano.codigo}
              className={`rounded-lg border bg-white p-4 ${
                plano.codigo === atual.codigo
                  ? 'border-marca-600 ring-1 ring-marca-600/20'
                  : 'border-slate-200'
              }`}
            >
              <p className="font-semibold text-slate-900">{plano.nome}</p>
              <p className="mt-1 text-lg text-slate-900">{reais(plano.precoCentavos)}</p>
              {plano.codigo === atual.codigo && (
                <p className="mt-2 text-xs font-medium text-marca-700">Plano atual</p>
              )}
            </div>
          ))}
        </div>
        {/* A troca de plano depende da integração de cobrança (F0-9). Enquanto não
            existe, é mais honesto dizer isso do que exibir um botão que não faz nada. */}
        <p className="mt-3 text-sm text-slate-500">
          A troca de plano ainda não está disponível por aqui. Fale com o suporte.
        </p>
      </section>
    </div>
  )
}
