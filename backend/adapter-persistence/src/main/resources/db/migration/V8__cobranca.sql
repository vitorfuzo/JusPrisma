-- Eventos recebidos do gateway de cobrança.
--
-- Todo gateway reentrega webhook: por timeout nosso, por falha de rede, por retentativa
-- programada. Se "pagamento confirmado" for processado duas vezes, o cliente ganha crédito
-- em dobro; se for "assinatura cancelada", ele perde acesso que pagou. A idempotência não
-- é refinamento — é o requisito central de quem recebe webhook.

CREATE TABLE evento_gateway (
    id                bigserial   PRIMARY KEY,
    gateway           text        NOT NULL,
    evento_id_externo text        NOT NULL,
    tipo              text        NOT NULL,
    payload           jsonb       NOT NULL,
    recebido_em       timestamptz NOT NULL DEFAULT now(),
    processado_em     timestamptz,
    erro              text
);

-- A chave da idempotência. O INSERT acontece antes do processamento: se a mesma entrega
-- chegar de novo, a violação de unicidade é o sinal de que já vimos aquele evento.
CREATE UNIQUE INDEX evento_gateway_unico ON evento_gateway (gateway, evento_id_externo);

-- Sustenta a varredura por eventos que entraram mas não completaram o processamento.
CREATE INDEX evento_gateway_pendente_idx ON evento_gateway (recebido_em)
    WHERE processado_em IS NULL;

COMMENT ON TABLE evento_gateway IS
    'Recebidos do gateway. Unicidade por (gateway, evento_id_externo) e o que garante idempotencia.';

-- Sem RLS: o webhook chega sem sessão e sem tenant, e o vínculo com o escritório só é
-- descoberto ao interpretar o payload. O isolamento aqui vem de a rota ser autenticada
-- pelo próprio gateway, não por usuário.
GRANT SELECT, INSERT, UPDATE ON evento_gateway TO jusprisma_app;
GRANT USAGE ON SEQUENCE evento_gateway_id_seq TO jusprisma_app;

-- ---------------------------------------------------------------------------
-- Rastreio do aviso de fim de degustação
-- ---------------------------------------------------------------------------

-- O aviso do dia 11 é promessa de produto: converter sem avisar gera chargeback e
-- reclamação. A coluna existe para que o job possa rodar todo dia sem reenviar o aviso a
-- quem já recebeu.
ALTER TABLE assinatura ADD COLUMN aviso_de_conversao_em timestamptz;

CREATE INDEX assinatura_trial_a_avisar_idx ON assinatura (fim_do_periodo)
    WHERE status = 'TRIAL' AND aviso_de_conversao_em IS NULL;

-- Localiza assinaturas que precisam de ação, atravessando tenants: o job roda sem
-- requisição e sem usuário, então não há app.tenant_id a definir. Exceção nomeada, como
-- as demais, devolvendo apenas o que o job precisa para agir.
CREATE FUNCTION cobranca_trials_a_avisar(p_limite_em timestamptz)
    RETURNS TABLE (assinatura_id uuid, tenant_id uuid, fim_do_periodo timestamptz)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT a.id, a.tenant_id, a.fim_do_periodo
          FROM assinatura a
         WHERE a.status = 'TRIAL'
           AND a.aviso_de_conversao_em IS NULL
           AND a.fim_do_periodo <= p_limite_em
    $$;

REVOKE ALL ON FUNCTION cobranca_trials_a_avisar(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_trials_a_avisar(timestamptz) TO jusprisma_app;

CREATE FUNCTION cobranca_trials_a_converter(p_agora timestamptz)
    RETURNS TABLE (assinatura_id uuid, tenant_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT a.id, a.tenant_id
          FROM assinatura a
         WHERE a.status = 'TRIAL'
           AND a.fim_do_periodo <= p_agora
    $$;

REVOKE ALL ON FUNCTION cobranca_trials_a_converter(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_trials_a_converter(timestamptz) TO jusprisma_app;

-- Marcações feitas pelo job, também sem tenant no contexto.
CREATE FUNCTION cobranca_marcar_aviso(p_assinatura uuid)
    RETURNS void
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
        UPDATE assinatura SET aviso_de_conversao_em = now()
         WHERE id = p_assinatura AND aviso_de_conversao_em IS NULL
    $$;

REVOKE ALL ON FUNCTION cobranca_marcar_aviso(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_marcar_aviso(uuid) TO jusprisma_app;

CREATE FUNCTION cobranca_atualizar_status(p_assinatura uuid, p_status text, p_proxima timestamptz)
    RETURNS void
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
        UPDATE assinatura
           SET status = p_status,
               proxima_cobranca = coalesce(p_proxima, proxima_cobranca)
         WHERE id = p_assinatura
    $$;

REVOKE ALL ON FUNCTION cobranca_atualizar_status(uuid, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_atualizar_status(uuid, text, timestamptz) TO jusprisma_app;

-- Localiza a assinatura a partir do identificador que o gateway conhece.
CREATE FUNCTION cobranca_assinatura_por_gateway(p_subscription_id text)
    RETURNS TABLE (assinatura_id uuid, tenant_id uuid, plano_codigo text, status text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT a.id, a.tenant_id, a.plano_codigo, a.status
          FROM assinatura a
         WHERE a.gateway_subscription_id = p_subscription_id
    $$;

REVOKE ALL ON FUNCTION cobranca_assinatura_por_gateway(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_assinatura_por_gateway(text) TO jusprisma_app;
