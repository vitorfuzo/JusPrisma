-- Sessões de renovação, com rotação e detecção de reuso.
--
-- Cada login abre uma família. Cada renovação consome um token e emite o próximo da mesma
-- família. Um token só pode ser consumido uma vez.
--
-- O reuso é o sinal que importa: se um token já consumido volta a aparecer, ou o cliente
-- legítimo está repetindo uma requisição, ou alguém copiou o token. Não há como distinguir
-- os dois casos, então a família inteira é revogada — o legítimo refaz o login, e o ladrão
-- perde o acesso junto. Aceitar um reuso em silêncio significaria que um token roubado vale
-- para sempre, porque o ladrão simplesmente renova antes da vítima.

CREATE TABLE sessao_refresh (
    id          uuid        PRIMARY KEY,
    familia_id  uuid        NOT NULL,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id)  ON DELETE CASCADE,
    usuario_id  uuid        NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    criado_em   timestamptz NOT NULL DEFAULT now(),
    expira_em   timestamptz NOT NULL,
    usado_em    timestamptz,
    revogado_em timestamptz,
    motivo_revogacao text,
    ip          inet,
    user_agent  text,
    CONSTRAINT sessao_refresh_motivo_valido CHECK (
        motivo_revogacao IS NULL
        OR motivo_revogacao IN ('LOGOUT', 'REUSO_DETECTADO', 'ROTACAO', 'TROCA_DE_SENHA')
    )
);

-- O id é o jti do token, gerado pela aplicação. Não há DEFAULT: um id que o banco
-- inventasse não teria como corresponder ao token já emitido.
COMMENT ON COLUMN sessao_refresh.id IS 'jti do token de renovacao, gerado pela aplicacao';
COMMENT ON COLUMN sessao_refresh.familia_id IS
    'Agrupa a cadeia de rotacoes de um mesmo login. Revogada inteira quando se detecta reuso.';

CREATE INDEX sessao_refresh_familia_idx ON sessao_refresh (familia_id);
CREATE INDEX sessao_refresh_usuario_idx ON sessao_refresh (usuario_id);
-- Sustenta a limpeza periódica de sessões vencidas.
CREATE INDEX sessao_refresh_expiracao_idx ON sessao_refresh (expira_em)
    WHERE revogado_em IS NULL;

ALTER TABLE sessao_refresh ENABLE ROW LEVEL SECURITY;
ALTER TABLE sessao_refresh FORCE  ROW LEVEL SECURITY;

CREATE POLICY sessao_refresh_isolamento ON sessao_refresh
    USING (tenant_id = app_tenant_atual())
    WITH CHECK (tenant_id = app_tenant_atual());

GRANT SELECT, INSERT, UPDATE, DELETE ON sessao_refresh TO jusprisma_app;
