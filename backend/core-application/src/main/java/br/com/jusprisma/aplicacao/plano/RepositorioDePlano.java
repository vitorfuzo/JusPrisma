package br.com.jusprisma.aplicacao.plano;

import br.com.jusprisma.dominio.plano.Plano;

import java.util.List;
import java.util.Optional;

/** Catálogo de planos. Somente leitura em runtime: preço e cota mudam por operação administrativa. */
public interface RepositorioDePlano {

    Optional<Plano> buscar(String codigo);

    List<Plano> listarAtivos();
}
