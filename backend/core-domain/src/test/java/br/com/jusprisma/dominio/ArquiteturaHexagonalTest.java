package br.com.jusprisma.dominio;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guarda a regra do hexágono declarada no CLAUDE.md: o domínio não conhece framework
 * nem fonte de dados.
 *
 * <p>Sem este teste a regra é só intenção — a primeira anotação {@code @Entity} colocada
 * por conveniência dentro do domínio passa despercebida em revisão e, meses depois,
 * trocar de ORM ou de tribunal vira reescrita de regra de negócio.
 */
class ArquiteturaHexagonalTest {

    private static final JavaClasses CLASSES_DO_DOMINIO = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("br.com.jusprisma");

    @Test
    @DisplayName("o domínio não depende de nenhum framework")
    void dominioNaoDependeDeFramework() {
        ArchRule regra = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "jakarta.validation..",
                        "com.fasterxml.jackson..",
                        "org.hibernate..",
                        "reactor..")
                .because("core-domain tem ZERO dependência de framework (CLAUDE.md, regra do hexágono)");

        regra.check(CLASSES_DO_DOMINIO);
    }

    @Test
    @DisplayName("o domínio não conhece nenhuma fonte de dados externa")
    void dominioNaoConheceFonteExterna() {
        ArchRule regra = noClasses()
                .should().haveNameMatching(".*(Tjdft|DataJud|Djen|Asaas|Anthropic).*")
                .because("fonte de dados é adapter atrás de porta; o domínio não sabe que TJDFT existe");

        regra.check(CLASSES_DO_DOMINIO);
    }
}
