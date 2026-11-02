package org.maplibre.mlt.research;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The layering of {@code docs/architecture.md}, enforced. The encoder side ({@code buffer},
 * {@code encoder}, {@code baseline}, {@code registry}) is what a decoder needs and never reaches
 * into measurement, probes, the workshop or the command line; every layer only depends downwards;
 * no two packages depend on each other in a cycle.
 */
class ArchitectureTest {

  private static final String R = "org.maplibre.mlt.research.";

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.maplibre.mlt.research");
  }

  @Test
  void layersOnlyDependDownwards() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(false)
        .layer("Buffer")
        .definedBy(R + "buffer..")
        .layer("Encoder")
        .definedBy(R + "encoder..")
        .layer("Baseline")
        .definedBy(R + "baseline..")
        .layer("Extraction")
        .definedBy(R + "extraction..")
        .layer("Registry")
        .definedBy(R + "registry..")
        .layer("Measurement")
        .definedBy(R + "measurement..")
        .layer("Workshop")
        .definedBy(R + "workshop..")
        .layer("Probe")
        .definedBy(R + "probe..")
        .layer("Cli")
        .definedBy(R + "cli..")
        .whereLayer("Cli")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Probe")
        .mayOnlyBeAccessedByLayers("Cli")
        .whereLayer("Workshop")
        .mayOnlyBeAccessedByLayers("Probe", "Cli")
        .whereLayer("Measurement")
        .mayOnlyBeAccessedByLayers("Workshop", "Probe", "Cli")
        .whereLayer("Registry")
        .mayOnlyBeAccessedByLayers("Measurement", "Workshop", "Probe", "Cli")
        .whereLayer("Baseline")
        .mayOnlyBeAccessedByLayers("Measurement", "Workshop", "Probe", "Cli")
        .whereLayer("Extraction")
        .mayOnlyBeAccessedByLayers("Measurement", "Workshop", "Probe", "Cli")
        .whereLayer("Encoder")
        .mayOnlyBeAccessedByLayers(
            "Baseline", "Registry", "Measurement", "Workshop", "Probe", "Cli")
        .check(classes);
  }

  @Test
  void theEncoderStagesOnlyDependDownwards() {
    layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(false)
        .layer("Format")
        .definedBy(R + "encoder.format..")
        .layer("Segmentation")
        .definedBy(R + "encoder.segmentation..")
        .layer("Prediction")
        .definedBy(R + "encoder.prediction..")
        .layer("Reference")
        .definedBy(R + "encoder.reference..")
        .layer("Reorder")
        .definedBy(R + "encoder.reorder..")
        .layer("Stream")
        .definedBy(R + "encoder.stream..")
        .whereLayer("Format")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("Segmentation")
        .mayOnlyBeAccessedByLayers("Format")
        .whereLayer("Prediction")
        .mayOnlyBeAccessedByLayers("Format", "Segmentation")
        .whereLayer("Reference")
        .mayOnlyBeAccessedByLayers("Format", "Segmentation", "Prediction")
        .whereLayer("Reorder")
        .mayOnlyBeAccessedByLayers("Format", "Segmentation", "Prediction", "Reference")
        .whereLayer("Stream")
        .mayOnlyBeAccessedByLayers("Format", "Segmentation")
        .check(classes);
  }

  @Test
  void packagesAreFreeOfCycles() {
    slices().matching(R + "(**)").should().beFreeOfCycles().check(classes);
  }

  @Test
  void theEncoderNeverReachesIntoMeasurement() {
    noClasses()
        .that()
        .resideInAnyPackage(R + "buffer..", R + "encoder..", R + "baseline..", R + "registry..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            R + "measurement..",
            R + "probe..",
            R + "workshop..",
            R + "cli..",
            R + "extraction..")
        .check(classes);
  }

  @Test
  void theEncoderIsUsableByADecoderWithoutAnyMeasurementCode() {
    classes()
        .that()
        .resideInAnyPackage(R + "buffer..", R + "encoder..")
        .should()
        .onlyDependOnClassesThat(
            resideInAnyPackage(
                R + "buffer..",
                R + "encoder..",
                "java.lang..",
                "java.util..",
                "java.io..",
                "java.nio..",
                "org.maplibre.mlt.converter..",
                "org.maplibre.mlt.decoder..",
                "me.lemire.integercompression.."))
        .check(classes);
  }

  @Test
  void theEncoderDoesNotUseTheCompressionBackends() {
    noClasses()
        .that()
        .resideInAnyPackage(R + "buffer..", R + "encoder..", R + "baseline..", R + "registry..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.aayushatharva..", "com.github.luben..", "java.util.zip..")
        .check(classes);
  }

  @Test
  void theCommandLineIsOnlyReachedFromItself() {
    classes()
        .that()
        .resideInAPackage(R + "cli..")
        .should()
        .onlyHaveDependentClassesThat()
        .resideInAPackage(R + "cli..")
        .check(classes);
  }
}
