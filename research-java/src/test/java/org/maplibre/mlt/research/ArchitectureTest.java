package org.maplibre.mlt.research;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The layering of {@code docs/architecture.md}: the encoder (pipeline and the contracts under it)
 * never reaches up into the entry points or the workshop, the contracts never reach into the
 * pipeline built on them, and extraction sits below the encoder (it fills the encoding\n * contract's {@code BufferContext}, nothing above it).
 */
class ArchitectureTest {

  private static final String ROOT = "org.maplibre.mlt.research.";

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.maplibre.mlt.research");
  }

  @Test
  void pipelineDoesNotDependOnTheEntryPointsOrTheWorkshop() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "pipeline..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + "cli..", ROOT + "workshop..")
        .check(classes);
  }

  @Test
  void encodingDoesNotDependOnThePipelineTheEntryPointsOrTheWorkshop() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "encoding..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + "pipeline..", ROOT + "cli..", ROOT + "workshop..")
        .check(classes);
  }

  @Test
  void extractionOnlyReadsIntoTheEncodingContract() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "extraction..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            ROOT + "pipeline..", ROOT + "cli..", ROOT + "workshop..")
        .check(classes);
  }

  @Test
  void theWorkshopDoesNotDependOnTheEntryPoints() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "workshop..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + "cli..")
        .check(classes);
  }
}
