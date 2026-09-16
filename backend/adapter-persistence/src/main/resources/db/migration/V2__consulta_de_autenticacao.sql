-- A única leitura do sistema que atravessa a fronteira de tenant.
--
-- No login ainda não se sabe a qual tenant o e-mail pertence, então a consulta não tem
-- como declarar app.tenant_id e as policies a bloqueariam. A alternativa seria afrouxar a
-- policy da tabela usuario, o que abriria a leitura de todos os usuários para qualquer
-- caminho de código. Preferimos uma exceção estreita, nomeada e auditável.
--
-- Três cuidados que fazem esta função ser segura:
--   1. Devolve apenas os campos que a autenticação precisa. Nada de nome, OAB ou histórico.
--   2. search_path fixo em public. Sem isso, um schema malicioso no search_path do chamador
--      poderia sequestrar a resolução de 'usuario' e a função executaria com os privilégios
--      do dono sobre uma tabela plantada.
--   3. Não recebe nem devolve nada além do e-mail e do material de conferência, de forma
--      que não sirva de canal para listar a base.

CREATE FUNCTION credenciais_por_email(p_email text)
    RETURNS TABLE (
        usuario_id         uuid,
        tenant_id          uuid,
        senha_hash         text,
        papel              text,
        email_verificado   boolean,
        tenant_operacional boolean
    )
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
        SELECT u.id,
               u.tenant_id,
               u.senha_hash,
               u.papel,
               u.email_verificado_em IS NOT NULL,
               t.status = 'ATIVO'
          FROM usuario u
          JOIN tenant  t ON t.id = u.tenant_id
         WHERE lower(u.email) = lower(p_email)
    $$;

COMMENT ON FUNCTION credenciais_por_email(text) IS
    'Excecao nomeada ao RLS, restrita ao login. Devolve so o material de autenticacao. Nao use para mais nada.';

-- Revoga o acesso amplo antes de conceder ao que precisa: por padrão o PostgreSQL concede
-- EXECUTE a PUBLIC em funções novas, o que aqui significaria expor a consulta a qualquer
-- role que venha a existir no futuro.
REVOKE ALL ON FUNCTION credenciais_por_email(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION credenciais_por_email(text) TO jusprisma_app;
