import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ErroDaApi } from './api/cliente'
import { ProvedorDeAutenticacao } from './auth/ContextoDeAutenticacao'
import { RotaProtegida } from './auth/RotaProtegida'
import { AceitarConvite } from './paginas/AceitarConvite'
import { CriarConta } from './paginas/CriarConta'
import { Entrar } from './paginas/Entrar'
import { Equipe } from './paginas/Equipe'
import { Inicio } from './paginas/Inicio'
import { MolduraInterna } from './paginas/MolduraInterna'
import { Planos } from './paginas/Planos'
import { RecuperarSenha } from './paginas/RecuperarSenha'
import { RedefinirSenha } from './paginas/RedefinirSenha'
import { VerificarEmail } from './paginas/VerificarEmail'

const fila = new QueryClient({
  defaultOptions: {
    queries: {
      // Não repetir erro do cliente: 401, 403, 404 e 429 não melhoram com insistência, e
      // repetir um 429 é justamente empurrar o limite que acabou de ser atingido.
      retry: (tentativas, erro) => {
        if (erro instanceof ErroDaApi && erro.status < 500) return false
        return tentativas < 2
      },
      staleTime: 30_000,
    },
  },
})

export function App() {
  return (
    <QueryClientProvider client={fila}>
      <BrowserRouter>
        <ProvedorDeAutenticacao>
          <Routes>
            {/* Públicas */}
            <Route path="/entrar" element={<Entrar />} />
            <Route path="/criar-conta" element={<CriarConta />} />
            <Route path="/recuperar-senha" element={<RecuperarSenha />} />
            <Route path="/redefinir-senha" element={<RedefinirSenha />} />
            <Route path="/verificar-email" element={<VerificarEmail />} />
            <Route path="/aceitar-convite" element={<AceitarConvite />} />

            {/* Exigem sessão */}
            <Route element={<RotaProtegida />}>
              <Route element={<MolduraInterna />}>
                <Route index element={<Inicio />} />
                <Route path="/planos" element={<Planos />} />
                <Route path="/equipe" element={<Equipe />} />
              </Route>
            </Route>
          </Routes>
        </ProvedorDeAutenticacao>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
