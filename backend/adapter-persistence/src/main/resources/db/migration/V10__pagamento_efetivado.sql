-- Idempotência por pagamento, além da idempotência por evento de V8.
--
-- O Asaas notifica o mesmo pagamento mais de uma vez com ids de evento diferentes: no
-- cartão, PAYMENT_CONFIRMED na aprovação e PAYMENT_RECEIVED na liquidação; no boleto, os
-- dois também. A unicidade de evento_gateway não pega isso, e a recarga do mês saía em
-- dobro. Esta tabela registra o pagamento cujo efeito já foi aplicado; a unicidade em
-- (gateway, pagamento_id_externo) decide, como em V8, sem consulta prévia que perca a corrida.
--
-- Migração aditiva: tabela nova, nada existente muda. O backfill copia os pagamentos que
-- já tiveram efeito, para que um cartão confirmado antes desta versão e liquidado depois
-- não credite de novo. Lê só evento_gateway, que é pequena (um evento por cobrança).
--
-- Desfazer:
--   DROP TABLE pagamento_efetivado;
-- Sem perda: os eventos de origem continuam em evento_gateway.

CREATE TABLE pagamento_efetivado (
    gateway              text        NOT NULL,
    pagamento_id_externo text        NOT NULL,
    evento_id_externo    text        NOT NULL,
    efetivado_em         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (gateway, pagamento_id_externo)
);

COMMENT ON TABLE pagamento_efetivado IS
    'Pagamento cujo efeito (ativacao, plano, recarga) ja foi aplicado. Unicidade por (gateway, pagamento) evita recarga em dobro.';

-- O primeiro evento de cada pagamento é o que teve efeito; os seguintes, depois desta
-- versão, passam a ser reconhecidos como repetição.
INSERT INTO pagamento_efetivado (gateway, pagamento_id_externo, evento_id_externo, efetivado_em)
SELECT DISTINCT ON (gateway, payload -> 'payment' ->> 'id')
       gateway, payload -> 'payment' ->> 'id', evento_id_externo, recebido_em
  FROM evento_gateway
 WHERE tipo = 'PAGAMENTO_CONFIRMADO'
   AND jsonb_typeof(payload) = 'object'
   AND coalesce(payload -> 'payment' ->> 'id', '') <> ''
 ORDER BY gateway, payload -> 'payment' ->> 'id', recebido_em, id;

-- Sem RLS, pelo mesmo motivo de evento_gateway: o webhook chega sem tenant.
GRANT SELECT, INSERT ON pagamento_efetivado TO jusprisma_app;
