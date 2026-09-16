-- Operadores da plataforma.
--
-- Não são usuários de escritório: é a equipe do JusPrisma, que precisa ver consumo,
-- investigar problema e conceder crédito manualmente quando algo falha do nosso lado.
--
-- Tabela separada de propósito. Uma coluna "é_admin" em usuario seria mais simples e
-- muito pior: bastaria um UPDATE indevido, ou um bug de cadastro, para um cliente virar
-- operador da plataforma. Separando, o privilégio não tem como ser concedido por um
-- caminho que o produto expõe ao público.

CREATE TABLE administrador (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email      text        NOT NULL,
    senha_hash text        NOT NULL,
    nome       text        NOT NULL,
    ativo      boolean     NOT NULL DEFAULT true,
    criado_em  timestamptz NOT NULL DEFAULT now(),
    ultimo_acesso_em timestamptz
);

CREATE UNIQUE INDEX administrador_email_unico ON administrador (lower(email));

-- Sem RLS: administrador não pertence a tenant nenhum. O isolamento aqui vem de o acesso
-- ser por credencial separada e as rotas exigirem escopo próprio no token.
GRANT SELECT, INSERT, UPDATE ON administrador TO jusprisma_app;

-- ---------------------------------------------------------------------------
-- Trilha de auditoria
-- ---------------------------------------------------------------------------

-- Toda ação de operador sobre conta de cliente fica registrada. Conceder crédito é mexer
-- em algo que vale dinheiro; sem trilha, não há como distinguir correção legítima de
-- fraude interna, nem responder a um cliente que questione o próprio extrato.
CREATE TABLE acao_administrativa (
    id               bigserial   PRIMARY KEY,
    administrador_id uuid        NOT NULL REFERENCES administrador (id) ON DELETE RESTRICT,
    acao             text        NOT NULL,
    tenant_alvo      uuid        REFERENCES tenant (id) ON DELETE SET NULL,
    detalhe          jsonb,
    criado_em        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX acao_administrativa_admin_idx ON acao_administrativa (administrador_id, criado_em DESC);
CREATE INDEX acao_administrativa_tenant_idx ON acao_administrativa (tenant_alvo, criado_em DESC);

-- Append-only pelo mesmo motivo do ledger, e com a mesma proteção em dois níveis:
-- trilha que pode ser editada não é trilha.
GRANT SELECT, INSERT ON acao_administrativa TO jusprisma_app;
GRANT USAGE ON SEQUENCE acao_administrativa_id_seq TO jusprisma_app;

CREATE TRIGGER acao_administrativa_append_only
    BEFORE UPDATE OR DELETE ON acao_administrativa
    FOR EACH ROW EXECUTE FUNCTION recusar_alteracao_de_lancamento();

-- ---------------------------------------------------------------------------
-- Leitura administrativa, atravessando tenants
-- ---------------------------------------------------------------------------

-- O painel precisa listar todos os escritórios, e o RLS existe justamente para impedir
-- isso. Mais uma exceção nomeada e estreita, como no login: devolve só o que o painel
-- mostra, e nada do conteúdo de trabalho dos clientes — nenhuma decisão, nenhum processo,
-- nenhum documento.
CREATE FUNCTION painel_listar_tenants(p_limite integer DEFAULT 100, p_deslocamento integer DEFAULT 0)
    RETURNS TABLE (
        tenant_id     uuid,
        nome          text,
        status        text,
        criado_em     timestamptz,
        plano_codigo  text,
        status_assinatura text,
        usuarios      bigint
    )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT t.id, t.nome, t.status, t.criado_em,
               a.plano_codigo, a.status,
               (SELECT count(*) FROM usuario u WHERE u.tenant_id = t.id)
          FROM tenant t
          LEFT JOIN assinatura a
                 ON a.tenant_id = t.id
                AND a.status IN ('TRIAL', 'ATIVA', 'INADIMPLENTE')
         ORDER BY t.criado_em DESC
         LIMIT p_limite OFFSET p_deslocamento
    $$;

COMMENT ON FUNCTION painel_listar_tenants(integer, integer) IS
    'Excecao nomeada ao RLS, restrita ao painel administrativo. Nao expoe conteudo de trabalho dos clientes.';

REVOKE ALL ON FUNCTION painel_listar_tenants(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION painel_listar_tenants(integer, integer) TO jusprisma_app;

CREATE FUNCTION painel_consumo_do_tenant(p_tenant uuid)
    RETURNS TABLE (tipo_credito text, saldo bigint, consumido bigint)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT c.tipo_credito,
               sum(c.delta),
               -sum(c.delta) FILTER (WHERE c.delta < 0)
          FROM credito_lancamento c
         WHERE c.tenant_id = p_tenant
         GROUP BY c.tipo_credito
    $$;

REVOKE ALL ON FUNCTION painel_consumo_do_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION painel_consumo_do_tenant(uuid) TO jusprisma_app;
