-- Planos, assinatura e o ledger de créditos.
--
-- Duas invariantes de negócio moram aqui e são garantidas pelo banco, não pela aplicação:
--   1. Cota e preço sao DADO, nunca codigo. Mudar o preco do Pro e um UPDATE.
--   2. O ledger e append-only. Saldo e soma, nunca campo.

-- ---------------------------------------------------------------------------
-- Planos
-- ---------------------------------------------------------------------------

CREATE TABLE plano (
    codigo         text        PRIMARY KEY,
    nome           text        NOT NULL,
    preco_centavos integer     NOT NULL CHECK (preco_centavos >= 0),
    limites        jsonb       NOT NULL,
    ativo          boolean     NOT NULL DEFAULT true,
    atualizado_em  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN plano.limites IS
    'Cotas e recursos do plano. null numa cota significa ilimitado; cota ausente significa negado.';

-- Plano é catálogo público, igual para todos os tenants: sem RLS, e só leitura para a
-- aplicação. Mexer em preço ou cota é operação administrativa, não de runtime.
GRANT SELECT ON plano TO jusprisma_app;

-- Ausência de chave é negação, não permissão: se alguém adicionar uma cota nova ao código
-- e esquecer de incluí-la aqui, o resultado é bloqueio visível, não liberação silenciosa.
INSERT INTO plano (codigo, nome, preco_centavos, limites) VALUES
('DEGUSTACAO', 'Degustação', 990, '{
    "cotas": {
        "PERFIS_NOVOS_MES": 2,
        "COMPARACAO_MAGISTRADOS": 0,
        "IA_MENSAGENS_MES": 30,
        "CALCULOS": 2,
        "ASSINATURAS": 2,
        "CONSULTAS": 0,
        "MONITORAMENTO_PROCESSOS": 10,
        "DRIVE_GB": 1,
        "SUBUSUARIOS": 0,
        "ROLLOVER_MESES": 0
    },
    "recursos": []
}'::jsonb),
('SOLO', 'Solo', 5700, '{
    "cotas": {
        "PERFIS_NOVOS_MES": 10,
        "COMPARACAO_MAGISTRADOS": 2,
        "IA_MENSAGENS_MES": 150,
        "CALCULOS": 10,
        "ASSINATURAS": 10,
        "CONSULTAS": 5,
        "MONITORAMENTO_PROCESSOS": null,
        "DRIVE_GB": 10,
        "SUBUSUARIOS": 0,
        "ROLLOVER_MESES": 0
    },
    "recursos": []
}'::jsonb),
('PRO', 'Pro', 13700, '{
    "cotas": {
        "PERFIS_NOVOS_MES": 50,
        "COMPARACAO_MAGISTRADOS": 5,
        "IA_MENSAGENS_MES": 600,
        "CALCULOS": 40,
        "ASSINATURAS": 40,
        "CONSULTAS": 20,
        "MONITORAMENTO_PROCESSOS": null,
        "DRIVE_GB": 50,
        "SUBUSUARIOS": 3,
        "ROLLOVER_MESES": 1
    },
    "recursos": ["ALERTA_VIRADA_ENTENDIMENTO", "PDF_WHITE_LABEL"]
}'::jsonb),
('ESCRITORIO', 'Escritório', 29700, '{
    "cotas": {
        "PERFIS_NOVOS_MES": 200,
        "COMPARACAO_MAGISTRADOS": null,
        "IA_MENSAGENS_MES": null,
        "CALCULOS": null,
        "ASSINATURAS": 150,
        "CONSULTAS": 60,
        "MONITORAMENTO_PROCESSOS": null,
        "DRIVE_GB": 200,
        "SUBUSUARIOS": 10,
        "ROLLOVER_MESES": 1
    },
    "recursos": ["ALERTA_VIRADA_ENTENDIMENTO", "PDF_WHITE_LABEL", "API"]
}'::jsonb);

-- ---------------------------------------------------------------------------
-- Assinatura
-- ---------------------------------------------------------------------------

CREATE TABLE assinatura (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    plano_codigo           text        NOT NULL REFERENCES plano (codigo),
    status                 text        NOT NULL,
    inicio_em              timestamptz NOT NULL DEFAULT now(),
    fim_do_periodo         timestamptz,
    proxima_cobranca       timestamptz,
    -- Preenchidos pela integração de cobrança (F0-9). Nulos até lá.
    gateway_customer_id    text,
    gateway_subscription_id text,
    criado_em              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT assinatura_status_valido CHECK (
        status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE', 'CANCELADA')
    )
);

-- Uma assinatura vigente por tenant. Índice parcial porque assinaturas encerradas ficam
-- no histórico e não devem impedir a próxima.
CREATE UNIQUE INDEX assinatura_vigente_unica
    ON assinatura (tenant_id)
    WHERE status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE');

ALTER TABLE assinatura ENABLE ROW LEVEL SECURITY;
ALTER TABLE assinatura FORCE  ROW LEVEL SECURITY;

CREATE POLICY assinatura_isolamento ON assinatura
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

GRANT SELECT, INSERT, UPDATE ON assinatura TO jusprisma_app;

-- ---------------------------------------------------------------------------
-- Ledger de créditos
-- ---------------------------------------------------------------------------

CREATE TABLE credito_lancamento (
    id            bigserial   PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    tipo_credito  text        NOT NULL,
    delta         integer     NOT NULL CHECK (delta <> 0),
    motivo        text        NOT NULL,
    referencia_id text,
    estorno_de    bigint      REFERENCES credito_lancamento (id),
    criado_em     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credito_tipo_valido CHECK (
        tipo_credito IN ('PERFIL', 'IA', 'CALCULO', 'CONSULTA', 'ASSINATURA')
    )
);

COMMENT ON TABLE credito_lancamento IS
    'Append-only. Saldo = SUM(delta) por (tenant, tipo). Estorno e lancamento novo apontando para o original.';

CREATE INDEX credito_saldo_idx ON credito_lancamento (tenant_id, tipo_credito);
-- Um estorno por lançamento: impede estornar duas vezes o mesmo débito.
CREATE UNIQUE INDEX credito_estorno_unico ON credito_lancamento (estorno_de)
    WHERE estorno_de IS NOT NULL;

ALTER TABLE credito_lancamento ENABLE ROW LEVEL SECURITY;
ALTER TABLE credito_lancamento FORCE  ROW LEVEL SECURITY;

CREATE POLICY credito_lancamento_isolamento ON credito_lancamento
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

-- Append-only, garantido em dois níveis.
--
-- Primeiro: a aplicação não recebe UPDATE nem DELETE nesta tabela. Note a ausência
-- deliberada deles no GRANT abaixo.
GRANT SELECT, INSERT ON credito_lancamento TO jusprisma_app;
GRANT USAGE ON SEQUENCE credito_lancamento_id_seq TO jusprisma_app;

-- Segundo: um gatilho recusa a alteração mesmo que alguém conceda o privilégio depois.
-- Sem isso, um GRANT bem-intencionado durante uma correção de produção transformaria o
-- ledger em tabela comum, e o extrato do cliente deixaria de ser reconstituível.
CREATE FUNCTION recusar_alteracao_de_lancamento() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        RAISE EXCEPTION
            'credito_lancamento e append-only: % nao e permitido. Estorno e lancamento novo.',
            TG_OP;
    END;
    $$;

CREATE TRIGGER credito_lancamento_append_only
    BEFORE UPDATE OR DELETE ON credito_lancamento
    FOR EACH ROW EXECUTE FUNCTION recusar_alteracao_de_lancamento();
