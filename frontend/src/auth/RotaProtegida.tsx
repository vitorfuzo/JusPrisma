import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAutenticacao } from './useAutenticacao'

/**
 * Barra rotas que exigem sessão.
 *
 * Isto é conveniência de navegação, não segurança: quem autoriza de verdade é o servidor,
 * que valida o token a cada requisição e aplica Row Level Security no banco. Contornar
 * esta verificação no navegador leva a telas vazias e 401, não a dados de outro escritório.
 */
export function RotaProtegida() {
  const { sessao, carregando } = useAutenticacao()
  const local = useLocation()

  if (carregando) {
    // Enquanto a renovação de inicialização não responde, não dá para saber se há sessão.
    // Redirecionar agora jogaria para o login todo mundo que recarregou a página.
    return (
      <div className="flex min-h-dvh items-center justify-center text-slate-500">
        Carregando…
      </div>
    )
  }

  if (!sessao) {
    // Guarda de onde a pessoa veio, para devolvê-la ao destino depois do login.
    return <Navigate to="/entrar" replace state={{ de: local.pathname }} />
  }

  return <Outlet />
}
