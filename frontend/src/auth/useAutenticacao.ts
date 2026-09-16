import { useContext } from 'react'
import { ContextoAutenticacao, type ValorDaAutenticacao } from './contexto'

export function useAutenticacao(): ValorDaAutenticacao {
  const contexto = useContext(ContextoAutenticacao)
  if (!contexto) {
    throw new Error('useAutenticacao precisa estar dentro de ProvedorDeAutenticacao')
  }
  return contexto
}
