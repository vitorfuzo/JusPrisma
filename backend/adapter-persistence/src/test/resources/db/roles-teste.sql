-- Espelha o que docker/postgres-init/01-roles.sh faz no ambiente de desenvolvimento.
-- Existe separado porque o Testcontainers sobe o Postgres sem o compose.
--
-- NOSUPERUSER e NOBYPASSRLS não são detalhe: qualquer um dos dois faria o teste
-- de isolamento passar por engano, medindo um banco que não é o de produção.
CREATE ROLE jusprisma_app
    LOGIN
    PASSWORD 'app_teste'
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOBYPASSRLS;

GRANT USAGE ON SCHEMA public TO jusprisma_app;
