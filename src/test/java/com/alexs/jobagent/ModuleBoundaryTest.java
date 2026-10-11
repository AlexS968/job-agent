package com.alexs.jobagent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {
    @Test
    void modulesDoNotDependOnOtherModulesRepositories() {
        var production = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.alexs.jobagent");
        noClasses().that().resideInAPackage("..source..")
                .should().dependOnClassesThat().resideInAnyPackage("..run.repository..", "..vacancy.repository..")
                .check(production);
        noClasses().that().resideInAPackage("..run..")
                .should().dependOnClassesThat().resideInAnyPackage("..source.repository..", "..vacancy.repository..")
                .check(production);
        noClasses().that().resideInAPackage("..vacancy..")
                .should().dependOnClassesThat().resideInAnyPackage("..source.repository..", "..run.repository..")
                .check(production);
    }
}
