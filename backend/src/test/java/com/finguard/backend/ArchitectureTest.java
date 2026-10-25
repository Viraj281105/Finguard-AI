package com.finguard.backend;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the module boundaries described in docs/ARCHITECTURE.md:
 * {@code common} and {@code ai} depend on nothing in the app; {@code identity} is the base;
 * {@code ledger} builds on {@code identity}; {@code categorization} joins {@code ledger} and {@code ai};
 * {@code ingestion} builds on all of them.
 */
@AnalyzeClasses(packages = "com.finguard.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule commonDependsOnNoModule = noClasses().that().resideInAPackage("..backend.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..backend.identity..", "..backend.ledger..", "..backend.ingestion..", "..backend.ai..",
                    "..backend.categorization..", "..backend.intelligence..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule aiDependsOnNoModule = noClasses().that().resideInAPackage("..backend.ai..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..backend.identity..", "..backend.ledger..", "..backend.ingestion..",
                    "..backend.categorization..", "..backend.intelligence..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule identityIsTheBase = noClasses().that().resideInAPackage("..backend.identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..backend.ledger..", "..backend.ingestion..", "..backend.ai..", "..backend.categorization..",
                    "..backend.intelligence..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule ledgerDoesNotDependOnImportAiOrCategorization = noClasses()
            .that().resideInAPackage("..backend.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..backend.ingestion..", "..backend.ai..", "..backend.categorization..",
                    "..backend.intelligence..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule categorizationDoesNotDependOnIngestionOrIntelligence = noClasses()
            .that().resideInAPackage("..backend.categorization..")
            .should().dependOnClassesThat().resideInAnyPackage("..backend.ingestion..", "..backend.intelligence..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule intelligenceDoesNotDependOnIngestionOrCategorization = noClasses()
            .that().resideInAPackage("..backend.intelligence..")
            .should().dependOnClassesThat().resideInAnyPackage("..backend.ingestion..", "..backend.categorization..", "..backend.budgeting..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule budgetingDoesNotDependOnIngestionAiCategorizationOrIntelligence = noClasses()
            .that().resideInAPackage("..backend.budgeting..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..backend.ingestion..", "..backend.ai..", "..backend.categorization..", "..backend.intelligence..", "..backend.forecasting..");

    @ArchTest
    static final ArchRule forecastingDoesNotDependOnIngestionAiOrCategorization = noClasses()
            .that().resideInAPackage("..backend.forecasting..")
            .should().dependOnClassesThat().resideInAnyPackage("..backend.ingestion..", "..backend.ai..", "..backend.categorization..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnUpperLayers = noClasses()
            .that().haveSimpleNameEndingWith("Repository")
            .or().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule nothingDependsOnControllers = noClasses()
            .that().haveSimpleNameNotEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.finguard.backend.(*)..")
            .should().beFreeOfCycles();
}
