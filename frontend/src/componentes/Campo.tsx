import type { InputHTMLAttributes } from 'react'

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  rotulo: string
  erro?: string
}

export function Campo({ rotulo, erro, id, ...resto }: Props) {
  const identificador = id ?? resto.name ?? rotulo
  const idDoErro = `${identificador}-erro`

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={identificador} className="text-sm font-medium text-slate-700">
        {rotulo}
      </label>
      <input
        id={identificador}
        // aria-invalid e aria-describedby fazem o leitor de tela anunciar o erro junto
        // com o campo. Sem isso, a mensagem existe visualmente e não para quem navega
        // por teclado e leitor.
        aria-invalid={erro ? true : undefined}
        aria-describedby={erro ? idDoErro : undefined}
        className={`rounded-md border px-3 py-2 text-slate-900 outline-none transition
          focus:ring-2 focus:ring-marca-600/30
          ${erro ? 'border-red-400 focus:border-red-500' : 'border-slate-300 focus:border-marca-600'}`}
        {...resto}
      />
      {erro && (
        <p id={idDoErro} className="text-sm text-red-600">
          {erro}
        </p>
      )}
    </div>
  )
}
