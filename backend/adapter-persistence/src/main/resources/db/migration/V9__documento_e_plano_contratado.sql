-- Dados de contratação: documento de cobrança e plano escolhido aguardando pagamento.
--
-- Migração só aditiva. As duas colunas nascem nulas e sem constraint, e nulo é o valor
-- correto para toda linha existente: ninguém informou documento nem contratou plano ainda.
-- Não há backfill.
--
-- Desfazer:
--   DROP FUNCTION cobranca_efetivar_plano_contratado(uuid);
--   ALTER TABLE assinatura DROP COLUMN plano_contratado;
--   ALTER TABLE tenant DROP COLUMN documento_cobranca;
-- O último passo destrói os documentos já informados. Depois que houver contratação real,
-- é irreversível sem backup das linhas com documento_cobranca preenchido.

-- O Asaas recusa criar assinatura sem CPF ou CNPJ do pagador, e o advogado autônomo — o
-- público principal — só tem CPF. Por isso é "documento", e não reaproveita tenant.cnpj,
-- que é a identificação opcional informada no cadastro e tem outra finalidade.
--
-- CPF é dado pessoal (LGPD). Esta coluna serve apenas à cobrança: não é lida por consulta
-- analítica, não é exibida inteira em resposta e não aparece em log.
ALTER TABLE tenant ADD COLUMN documento_cobranca text;

ALTER TABLE tenant ADD CONSTRAINT tenant_documento_cobranca_formato
    CHECK (documento_cobranca IS NULL OR documento_cobranca ~ '^([0-9]{11}|[0-9]{14})$');

COMMENT ON COLUMN tenant.documento_cobranca IS
    'CPF (11) ou CNPJ (14), so digitos, validado. Uso exclusivo de cobranca; dado pessoal quando CPF.';

-- O plano escolhido fica pendente até o pagamento ser confirmado. Trocar plano_codigo na
-- hora da contratação liberaria os limites do plano pago — subusuários, recursos — antes
-- de qualquer cobrança entrar.
ALTER TABLE assinatura ADD COLUMN plano_contratado text REFERENCES plano (codigo);

COMMENT ON COLUMN assinatura.plano_contratado IS
    'Plano escolhido na contratacao, aguardando pagamento. Vira plano_codigo no primeiro pagamento confirmado.';

-- O webhook chega sem tenant no contexto, como as demais operações de cobrança em V8.
-- Efetiva o plano contratado e limpa a pendência numa operação só, para que um webhook
-- reentregue não tenha o que efetivar duas vezes.
CREATE FUNCTION cobranca_efetivar_plano_contratado(p_assinatura uuid)
    RETURNS text
    LANGUAGE sql
    SECURITY DEFINER
    SET search_path = public
    AS $$
        UPDATE assinatura
           SET plano_codigo = plano_contratado,
               plano_contratado = NULL
         WHERE id = p_assinatura
           AND plano_contratado IS NOT NULL
        RETURNING plano_codigo
    $$;

REVOKE ALL ON FUNCTION cobranca_efetivar_plano_contratado(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cobranca_efetivar_plano_contratado(uuid) TO jusprisma_app;
