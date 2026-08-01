package com.robsartin.marshal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().importPackages("com.robsartin.marshal");

    @Test
    void noSpringDependency() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework..")
                .check(classes);
    }

    @Test
    void mutableStateClassesImplementInvariant() {
        classes()
                .that()
                .haveSimpleNameEndingWith("State")
                .should()
                .implement(Invariant.class)
                .check(classes);
    }
}
