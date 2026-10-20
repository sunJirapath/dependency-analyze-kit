package io.github.sunjirapath.dak.maven;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

@ClassReferenceScannerTest.Marker
class ClassReferenceScannerTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {}

    @Test
    void findsTypesStoredOnlyInAnnotationMetadata() throws URISyntaxException, java.io.IOException {
        Path classes = Path.of(ClassReferenceScannerTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Set<String> references = ClassReferenceScanner.scan(Set.of(classes));
        String annotationType = "io.github.sunjirapath.dak.maven.ClassReferenceScannerTest";
        assertTrue(references.contains(annotationType + (char) 36 + "Marker"), references::toString);
    }
}
