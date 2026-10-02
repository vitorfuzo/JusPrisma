-- Cancelamento vale no fim do período já pago, e não no instante em que o gateway avisa.
--
-- Quem paga em 16/10 e cancela em 20/10 pagou até 16/11. Gravar CANCELADA na hora cortava
-- o acesso no dia 20 e, de quebra, derrubava a própria tela de planos. Agora o cancelamento
-- é agendado em cancela_em; até lá a assinatura segue no status que tinha.
--
-- Migração aditiva: coluna nova, nula para toda linha existente (nenhuma tem cancelamento
-- agendado), sem backfill. A função de aviso da degustação é substituída mantendo assinatura
-- e permissões; só ganha o filtro de cancelamento.
--
-- Desfazer:
--   DROP FUNCTION cobranca_encerrar_cancelamentos(timestamptz);
--   DROP FUNCTION cobranca_agendar_cancelamento(uuid, timestamptz);
--   recriar cobranca_trials_a_avisar como em V8;
--   ALTER TABLE assinatura DROP COLUMN cancela_em;
-- Antes de remover a coluna, encerre os agendamentos pendentes
-- (SELECT cobranca_encerrar_cancelamentos('infinity')), ou eles voltam a ter acesso indefinido.

ALTER TABLE assinatura ADD COLUMN cancela_em timestamptz;

COMMENT ON COLUMN assinatura.cancela_em IS
    'Fim do acesso de uma assinatura cancelada no gateway com periodo ja pago. Nulo: sem cancelamento agendado.';

-- Decide entre honrar o período e cancelar já, numa operação só, sem tenant no contexto
-- (o webhook chega sem ele, como em V8).
--   ATIVA com próxima cobrança no futuro -> acesso até a próxima cobrança.
--   TRIAL com fim no futuro              -> acesso até o fim da degustação, que foi paga.
--   qualquer outro caso (inadimplente, data já passada) -> CANCELADA agora.
-- Um cancelamento já agendado não é reagendado: a reentrega não muda a data.
CREATE FUNCTION cobranca_agendar_cancelamento(p_assinatura uuid, p_agora timestamptz)
    RETURNS text
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
        UPDATE assinatura
           SET cancela_em = CASE
                   WHEN status = 'ATIVA' AND proxima_cobranca > p_agora THEN proxima_cobranca
                   WHEN status = 'TRIAL' AND fim_do_periodo > p_agora THEN fim_do_periodo
               END,
               status = CASE
                   WHEN status = 'ATIVA' AND proxima_cobranca > p_agora THEN status
                   WHEN status = 'TRIAL' AND fim_do_periodo > p_agora THEN status
                   ELSE 'CANCELADA'
               END
         WHERE id = p_assinatura
           AND status <> 'CANCELADA'
           AND cancela_em IS NULL
        RETURNING status
    $$;

REVOKE ALL ON FUNCTION cobranca_agendar_cancelamento(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_agendar_cancelamento(uuid, timestamptz) TO jusprisma_app;

-- O job diário encerra o que venceu. O acesso já acaba na hora certa sem ele (o domínio
-- compara cancela_em com o relógio); o job só deixa o status coerente com a realidade.
CREATE FUNCTION cobranca_encerrar_cancelamentos(p_agora timestamptz)
    RETURNS integer
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
        WITH encerradas AS (
            UPDATE assinatura
               SET status = 'CANCELADA'
             WHERE cancela_em <= p_agora
               AND status <> 'CANCELADA'
            RETURNING 1
        )
        SELECT count(*)::integer FROM encerradas
    $$;

REVOKE ALL ON FUNCTION cobranca_encerrar_cancelamentos(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_encerrar_cancelamentos(timestamptz) TO jusprisma_app;

-- O aviso diz que a cobrança vai começar. Para quem cancelou, seria falso.
CREATE OR REPLACE FUNCTION cobranca_trials_a_avisar(p_limite_em timestamptz)
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
           AND a.cancela_em IS NULL
           AND a.fim_do_periodo <= p_limite_em
    $$;
