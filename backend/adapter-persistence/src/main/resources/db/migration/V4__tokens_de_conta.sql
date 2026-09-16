-- Tokens de uso único enviados por e-mail: verificação de endereço e recuperação de senha.
--
-- O que é guardado é o SHA-256 do token, nunca o token. Um vazamento do banco entrega
-- hashes, e hash não abre link nenhum. O valor original existe apenas no corpo do e-mail e
-- na memória do processo que o gerou.
--
-- A mesma tabela serve às duas finalidades porque o ciclo de vida é idêntico — emitir,
-- expirar, consumir uma vez. O que muda é só o prazo, e prazo é dado, não estrutura.

CREATE TABLE token_conta (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id)  ON DELETE CASCADE,
    usuario_id  uuid        NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    finalidade  text        NOT NULL,
    token_hash  bytea       NOT NULL,
    criado_em   timestamptz NOT NULL DEFAULT now(),
    expira_em   timestamptz NOT NULL,
    usado_em    timestamptz,
    CONSTRAINT token_conta_finalidade_valida CHECK (
        finalidade IN ('VERIFICACAO_EMAIL', 'RECUPERACAO_SENHA')
    )
);

COMMENT ON COLUMN token_conta.token_hash IS
    'SHA-256 do token. O valor original nunca e persistido.';

CREATE UNIQUE INDEX token_conta_hash_idx ON token_conta (token_hash);
CREATE INDEX token_conta_usuario_idx ON token_conta (usuario_id, finalidade);

ALTER TABLE token_conta ENABLE ROW LEVEL SECURITY;
ALTER TABLE token_conta FORCE  ROW LEVEL SECURITY;

CREATE POLICY token_conta_isolamento ON token_conta
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON token_conta TO jusprisma_app;

-- ---------------------------------------------------------------------------
-- Localização do token, antes de haver tenant
-- ---------------------------------------------------------------------------

-- Quem clica no link do e-mail não está autenticado e não informa tenant. Mesma situação
-- do login, mesma solução: exceção nomeada e estreita, em vez de afrouxar a policy.
--
-- Esta função devolve o suficiente para localizar e validar o token, e nada que sirva para
-- explorar a base: sem e-mail, sem nome, sem hash de senha. E recebe o hash, não o token,
-- de modo que nem em log de consulta lenta o valor original apareceria.
CREATE FUNCTION token_de_conta_por_hash(p_hash bytea)
    RETURNS TABLE (
        id         uuid,
        tenant_id  uuid,
        usuario_id uuid,
        finalidade text,
        expira_em  timestamptz,
        usado_em   timestamptz
    )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT t.id, t.tenant_id, t.usuario_id, t.finalidade, t.expira_em, t.usado_em
          FROM token_conta t
         WHERE t.token_hash = p_hash
    $$;

COMMENT ON FUNCTION token_de_conta_por_hash(bytea) IS
    'Excecao nomeada ao RLS, restrita aos fluxos de e-mail. Recebe hash, nunca o token.';

REVOKE ALL ON FUNCTION token_de_conta_por_hash(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION token_de_conta_por_hash(bytea) TO jusprisma_app;

-- Localiza a conta a partir do e-mail, para a recuperação de senha.
-- Devolve apenas identificadores: quem chama já precisa conhecer o e-mail para perguntar,
-- e a resposta ao usuário é idêntica exista ou não a conta.
CREATE FUNCTION conta_por_email(p_email text)
    RETURNS TABLE (usuario_id uuid, tenant_id uuid)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT u.id, u.tenant_id
          FROM usuario u
         WHERE lower(u.email) = lower(p_email)
    $$;

REVOKE ALL ON FUNCTION conta_por_email(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION conta_por_email(text) TO jusprisma_app;
