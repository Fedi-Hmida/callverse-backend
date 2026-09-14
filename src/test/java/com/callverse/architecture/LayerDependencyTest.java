package com.callverse.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

/**
 * Enforces the Clean Architecture dependency rule.
 *
 * <p><strong>Why this test exists.</strong> CallVerse is a single Maven module. A multi-module build
 * would have let the compiler enforce these boundaries: if {@code core} cannot see {@code host} on
 * its classpath, importing it is impossible rather than merely discouraged. A single module buys
 * simplicity at the cost of that guarantee, and this test is what buys the guarantee back. Without
 * it the layout is a naming convention, and naming conventions do not survive a deadline.
 *
 * <p>These rules fail the build, which is deliberate. A violation caught in review is caught by
 * whoever happens to be reviewing; a violation caught here is caught every time, including at
 * three in the morning before a demo.
 *
 * <p>If a rule below ever blocks legitimate work, the answer is to discuss the boundary and change
 * the rule deliberately, not to add an exclusion so the build goes green again.
 */
@AnalyzeClasses(
        packages = "com.callverse",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

    /**
     * The dependency rule itself: dependencies point inward, so the innermost layer depends on
     * nothing outside itself.
     */
    @ArchTest
    static final ArchRule core_must_not_depend_on_outer_layers =
            noClasses()
                    .that()
                    .resideInAPackage("..core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..host..", "..infrastructure..")
                    .because(
                            "dependencies point inward: business rules must not know about the web "
                                    + "layer or about any adapter, or they cannot be reasoned about, "
                                    + "tested, or reused by the simulation runner without dragging "
                                    + "HTTP and JPA along with them");

    /**
     * Keeps {@code core} framework-free. This is what makes the wiring pattern in
     * {@code infrastructure.config} enforceable rather than merely recommended: use cases are plain
     * Java, and infrastructure turns them into beans.
     */
    @ArchTest
    static final ArchRule core_must_not_depend_on_spring =
            noClasses()
                    .that()
                    .resideInAPackage("..core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework.web..",
                            "org.springframework.security..",
                            "org.springframework.boot..")
                    .because(
                            "use cases stay framework-free and are wired by infrastructure.config; "
                                    + "once @Service appears in core this rule becomes negotiable, "
                                    + "and a negotiable rule is not a rule");

    /**
     * The domain is the innermost ring. It describes the business, which existed before anyone
     * decided what the software could be asked to do.
     */
    @ArchTest
    static final ArchRule domain_must_not_depend_on_application =
            noClasses()
                    .that()
                    .resideInAPackage("..core.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..core.application..")
                    .because(
                            "the domain is the innermost ring: entities, enums and calculators "
                                    + "describe the relation center regardless of which use cases "
                                    + "happen to exist, so a domain type referencing a use case has "
                                    + "the dependency backwards");

    /**
     * The documented Ground Rule 2 compromise, encoded so that it stays a compromise rather than
     * spreading.
     *
     * <p>Domain entities carry JPA annotations deliberately: the stricter alternative of a
     * framework-free domain plus parallel JPA entities plus mappers costs roughly 25 extra classes
     * for a gain that is invisible from outside the system. The concession is bounded here: the
     * entities package may see {@code jakarta.persistence}, and nothing else under {@code core} may.
     * Without this rule, "the domain already imports JPA" becomes the argument for a
     * {@code TypedQuery} in a use case six months from now.
     */
    @ArchTest
    static final ArchRule only_entities_may_depend_on_jpa =
            noClasses()
                    .that()
                    .resideInAPackage("..core..")
                    .and()
                    .resideOutsideOfPackage("..core.domain.entities..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("jakarta.persistence..")
                    .because(
                            "JPA annotations in domain entities are a deliberate, documented "
                                    + "compromise (see README); it is bounded to the entities "
                                    + "package so that persistence concerns cannot leak into use "
                                    + "cases or domain calculators");

    /**
     * Controllers talk to application handlers, never to repositories. This is the rule that stops
     * the architecture quietly collapsing into a two-layer CRUD application.
     */
    @ArchTest
    static final ArchRule host_must_not_reach_into_persistence =
            noClasses()
                    .that()
                    .resideInAPackage("..host..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..infrastructure.persistence..")
                    .because(
                            "a controller that injects a repository has skipped the use case, and "
                                    + "with it every business rule, authorisation check and "
                                    + "transaction boundary the use case was responsible for");

    /** A cycle between layers means there are no layers, only a graph with optimistic names. */
    @ArchTest
    static final ArchRule layers_must_be_free_of_cycles =
            SlicesRuleDefinition.slices()
                    .matching("com.callverse.(*)..")
                    .should()
                    .beFreeOfCycles()
                    .because(
                            "core, host and infrastructure must form a one-way dependency graph; a "
                                    + "cycle means no layer can be understood, tested or deployed "
                                    + "without the others");

    /**
     * Placement rule rather than a dependency rule. It exists because the fastest way to erode a
     * feature-sliced layout is a controller that drifts into whatever package was convenient.
     */
    @ArchTest
    static final ArchRule controllers_live_only_in_the_host_layer =
            classes()
                    .that()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .resideInAPackage("..host.api.controllers..")
                    .because(
                            "the HTTP surface is discoverable in exactly one place, which is what "
                                    + "lets a reviewer see the whole API by listing one directory");
}
