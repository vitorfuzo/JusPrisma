#!/bin/bash
# Cria a role da aplicação, separada da role dona das tabelas.
#
# Essa separação é requisito de segurança, não organização: o dono das tabelas
# ignora as policies de Row Level Security por padrão. Se a aplicação conectasse
# como dona, o RLS existiria no schema e não teria efeito nenhum — o sistema
# pareceria isolado sem estar. Ver ADR 0001, seção 5.
#
# Roda uma única vez, na primeira criação do volume do Postgres.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE ${JUSPRISMA_DB_USER}
        LOGIN
        PASSWORD '${JUSPRISMA_DB_PASSWORD}'
        NOSUPERUSER
        NOCREATEDB
        NOCREATEROLE
        NOBYPASSRLS;

    COMMENT ON ROLE ${JUSPRISMA_DB_USER} IS
        'Role da aplicacao. NOBYPASSRLS e NOSUPERUSER sao obrigatorios: qualquer um dos dois desliga o Row Level Security silenciosamente.';

    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${JUSPRISMA_DB_USER};
    GRANT USAGE ON SCHEMA public TO ${JUSPRISMA_DB_USER};

    -- As tabelas ainda não existem; o Flyway as cria depois. Os grants de tabela
    -- ficam nas próprias migrations, junto das policies que as acompanham.
EOSQL

echo "Role ${JUSPRISMA_DB_USER} criada sem privilegio de bypass de RLS."
