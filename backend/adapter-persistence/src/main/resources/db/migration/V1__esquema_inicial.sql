-- Esquema inicial: conta, usuário e o isolamento entre tenants.
--
-- O isolamento é feito por Row Level Security no banco, não só por filtro no
-- repositório. Um WHERE esquecido num repositório vaza dados de outro escritório
-- de advocacia; a policy abaixo transforma esse esquecimento em zero linhas.
-- Ver ADR 0001, seção 5.

CREATE EXTENSION IF NOT EXISTS vector;     -- busca semântica (Fase 2)
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- similaridade de nome de magistrado
CREATE EXTENSION IF NOT EXISTS unaccent;   -- normalização de nome

-- ---------------------------------------------------------------------------
-- Tenant corrente
-- ---------------------------------------------------------------------------

-- Lê o tenant da transação corrente. O segundo argumento de current_setting
-- (missing_ok) evita erro quando a variável não foi definida: nesse caso a
-- função devolve NULL e as policies não casam com nada.
--
-- Falhar fechado é deliberado. Se um caminho de código esquecer de definir o
-- tenant, o resultado correto é "nenhuma linha", nunca "todas as linhas".
CREATE FUNCTION app_tenant_atual() RETURNS uuid
    LANGUAGE sql
    STABLE
    AS $$
        SELECT nullif(current_setting('app.tenant_id', true), '')::uuid
    $$;

COMMENT ON FUNCTION app_tenant_atual() IS
    'Tenant da transacao corrente, definido por SET LOCAL app.tenant_id. NULL quando ausente, o que faz as policies negarem tudo.';

-- ---------------------------------------------------------------------------
-- Tabelas
-- ---------------------------------------------------------------------------

CREATE TABLE tenant (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nome       text        NOT NULL,
    cnpj       text,
    status     text        NOT NULL,
    criado_em  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_status_valido CHECK (status IN ('ATIVO', 'SUSPENSO', 'CANCELADO'))
);

CREATE TABLE usuario (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    email                text        NOT NULL,
    senha_hash           text        NOT NULL,
    papel                text        NOT NULL,
    oab                  text,
    uf_oab               text,
    email_verificado_em  timestamptz,
    criado_em            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT usuario_papel_valido CHECK (papel IN ('OWNER', 'MEMBRO')),
    CONSTRAINT usuario_uf_oab_valida CHECK (uf_oab IS NULL OR uf_oab ~ '^[A-Z]{2}$')
);

-- E-mail é a credencial de login, logo único no sistema inteiro e insensível a caixa.
CREATE UNIQUE INDEX usuario_email_unico ON usuario (lower(email));
CREATE INDEX usuario_tenant_idx ON usuario (tenant_id);

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------

-- ENABLE liga a policy para quem não é dono da tabela.
-- FORCE a aplica também ao dono. Os dois são necessários: sem FORCE, qualquer
-- processo que conecte como dono lê tudo e o isolamento vira decoração.
-- (Roles SUPERUSER e BYPASSRLS ignoram ambos — por isso a role da aplicação é
-- criada com NOSUPERUSER e NOBYPASSRLS na infraestrutura.)

ALTER TABLE tenant  ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant  FORCE  ROW LEVEL SECURITY;
ALTER TABLE usuario ENABLE ROW LEVEL SECURITY;
ALTER TABLE usuario FORCE  ROW LEVEL SECURITY;

CREATE POLICY tenant_isolamento ON tenant
    USING (id = app_tenant_atual())
    WITH CHECK (id = app_tenant_atual());

CREATE POLICY usuario_isolamento ON usuario
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

-- ---------------------------------------------------------------------------
-- Privilégios da aplicação
-- ---------------------------------------------------------------------------

-- A role é criada pela infraestrutura (docker/postgres-init), não aqui:
-- senha de role não entra em migration versionada.
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant, usuario TO jusprisma_app;
GRANT EXECUTE ON FUNCTION app_tenant_atual() TO jusprisma_app;
