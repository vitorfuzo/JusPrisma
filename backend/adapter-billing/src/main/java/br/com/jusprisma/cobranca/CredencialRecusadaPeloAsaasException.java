package br.com.jusprisma.cobranca;

import br.com.jusprisma.aplicacao.cobranca.GatewayIndisponivelException;

/**
 * O Asaas recusou a nossa chave (401/403). Para o cliente é indisponibilidade, como as
 * demais; existe como tipo próprio só para o retry não repetir algo que não muda sozinho.
 */
class CredencialRecusadaPeloAsaasException extends GatewayIndisponivelException {

    CredencialRecusadaPeloAsaasException(Throwable causa) {
        super("credencial do gateway recusada", causa);
    }
}
