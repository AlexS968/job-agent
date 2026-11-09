package com.alexs.jobagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    private static final String[] MODULES = {"batch", "evaluation", "outbox", "run", "source", "vacancy"};

    @Test
    void modulesUseOtherModulesServicesInsteadOfTheirRepositoriesAndMappers() {
        var production = productionClasses();
        for (String owner : MODULES) {
            for (String target : MODULES) {
                if (!owner.equals(target)) {
                    noClasses().that().resideInAPackage(".." + owner + "..")
                            .should().dependOnClassesThat().resideInAnyPackage(
                                    ".." + target + ".repository..", ".." + target + ".mapper..")
                            .check(production);
                }
            }
        }
    }

    @Test
    void layersDoNotDependBackwards() {
        var production = productionClasses();
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..mapper..", "..controller..")
                .check(production);
        noClasses().that().resideInAPackage("..mapper..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..repository..", "..controller..")
                .check(production);
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..")
                .check(production);
        noClasses().that().resideInAPackage("..dto..")
                .should().dependOnClassesThat().resideInAPackage("..entity..")
                .check(production);
    }

    private static com.tngtech.archunit.core.domain.JavaClasses productionClasses() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.alexs.jobagent");
    }
}
