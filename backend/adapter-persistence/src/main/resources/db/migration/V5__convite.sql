-- Convites de subusuário.
--
-- Mesmo padrão dos tokens de conta: o que se guarda é o SHA-256 do segredo, nunca o
-- segredo. Aqui o cuidado é ainda maior, porque aceitar um convite não só dá acesso — cria
-- um usuário dentro de um escritório, com acesso aos processos e clientes dele.

CREATE TABLE convite (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id)  ON DELETE CASCADE,
    convidado_por uuid        NOT NULL REFERENCES usuario (id) ON DELETE RESTRICT,
    email         text        NOT NULL,
    papel         text        NOT NULL,
    token_hash    bytea       NOT NULL,
    criado_em     timestamptz NOT NULL DEFAULT now(),
    expira_em     timestamptz NOT NULL,
    aceito_em     timestamptz,
    revogado_em   timestamptz,
    CONSTRAINT convite_papel_valido CHECK (papel IN ('OWNER', 'MEMBRO'))
);

COMMENT ON COLUMN convite.token_hash IS
    'SHA-256 do segredo do convite. O valor original nunca e persistido.';
COMMENT ON COLUMN convite.convidado_por IS
    'ON DELETE RESTRICT: o historico de quem convidou quem nao pode sumir junto com o usuario.';

CREATE UNIQUE INDEX convite_hash_idx ON convite (token_hash);

-- Um convite pendente por e-mail dentro do mesmo escritório. Convidar duas vezes reenvia,
-- não acumula. Índice parcial porque convite aceito ou revogado não atrapalha um novo.
CREATE UNIQUE INDEX convite_pendente_unico
    ON convite (tenant_id, lower(email))
    WHERE aceito_em IS NULL AND revogado_em IS NULL;

CREATE INDEX convite_tenant_idx ON convite (tenant_id);

ALTER TABLE convite ENABLE ROW LEVEL SECURITY;
ALTER TABLE convite FORCE  ROW LEVEL SECURITY;

CREATE POLICY convite_isolamento ON convite
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON convite TO jusprisma_app;

-- ---------------------------------------------------------------------------
-- Localização do convite, antes de haver tenant
-- ---------------------------------------------------------------------------

-- Quem recebe o convite ainda não tem conta nem tenant. Mesma exceção estreita e nomeada
-- do login e dos tokens de e-mail.
--
-- Devolve o nome do escritório de propósito: a tela de aceite precisa dizer a que
-- escritório a pessoa está entrando. Entregar isso sem o nome faria o convidado aceitar
-- às cegas — e um convite é o momento em que alguém ganha acesso a dados de terceiros.
CREATE FUNCTION convite_por_hash(p_hash bytea)
    RETURNS TABLE (
        id                  uuid,
        tenant_id           uuid,
        nome_do_escritorio  text,
        email               text,
        papel               text,
        expira_em           timestamptz,
        aceito_em           timestamptz,
        revogado_em         timestamptz
    )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT c.id, c.tenant_id, t.nome, c.email, c.papel,
               c.expira_em, c.aceito_em, c.revogado_em
          FROM convite c
          JOIN tenant  t ON t.id = c.tenant_id
         WHERE c.token_hash = p_hash
    $$;

COMMENT ON FUNCTION convite_por_hash(bytea) IS
    'Excecao nomeada ao RLS, restrita ao aceite de convite. Recebe hash, nunca o segredo.';

REVOKE ALL ON FUNCTION convite_por_hash(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION convite_por_hash(bytea) TO jusprisma_app;
