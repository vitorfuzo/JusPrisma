-- Recontratação depois do cancelamento.
--
-- Quem teve a assinatura cancelada ficava sem nenhuma vigente e não conseguia contratar de
-- novo: a contratação exigia uma assinatura vigente para registrar o vínculo com o gateway.
-- A recontratação cria uma assinatura nova, que nasce em AGUARDANDO_PAGAMENTO: não dá acesso
-- (quem só clicou em contratar não volta a usar o produto, nem o saldo do período cancelado)
-- e vira ATIVA no primeiro pagamento confirmado, pelo caminho que já existe.
--
-- O índice único passa a cobrir a assinatura aguardando pagamento. Sem isso, dois cliques
-- simultâneos criariam duas, cada uma com a sua assinatura no Asaas e a sua cobrança.
--
-- Migração aditiva: nenhuma linha existente tem o status novo, então nenhuma viola o índice
-- novo (ele cobre o que o antigo cobria e mais um status vazio). O índice novo é criado antes
-- de o antigo cair, na mesma transação do Flyway: não há instante sem unicidade.
--
-- Desfazer (só enquanto não houver linha AGUARDANDO_PAGAMENTO; se houver, decida antes o que
-- fazer com elas, porque o CHECK antigo as recusa):
--   CREATE UNIQUE INDEX assinatura_vigente_unica ON assinatura (tenant_id)
--       WHERE status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE');
--   DROP INDEX assinatura_corrente_unica;
--   ALTER TABLE assinatura DROP CONSTRAINT assinatura_status_valido;
--   ALTER TABLE assinatura ADD CONSTRAINT assinatura_status_valido
--       CHECK (status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'CANCELADA'));

ALTER TABLE assinatura DROP CONSTRAINT assinatura_status_valido;
ALTER TABLE assinatura ADD CONSTRAINT assinatura_status_valido CHECK (
    status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'CANCELADA', 'AGUARDANDO_PAGAMENTO')
);

-- Uma assinatura corrente por tenant: a que dá acesso ou a que espera o primeiro pagamento.
-- A contratação usa este índice como alvo do ON CONFLICT.
CREATE UNIQUE INDEX assinatura_corrente_unica
    ON assinatura (tenant_id)
    WHERE status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'AGUARDANDO_PAGAMENTO');

DROP INDEX assinatura_vigente_unica;
