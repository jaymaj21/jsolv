package org.jsolv.generator;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LabelGeneratorTest {
    @TempDir Path temporary;

    private Path config(String text) throws Exception {
        Path file = temporary.resolve("jsolv.properties");
        Files.writeString(file, text);
        return file;
    }

    @Test void generatesAndRemovesOnlyOwnedObsoleteSources() throws Exception {
        Path output = temporary.resolve("generated");
        Path file = config("package=app.tags\nlabels=Persisted, Validated\n");
        assertEquals(2, LabelGenerator.generate(file, output));
        Path persisted = output.resolve("app/tags/Persisted.java");
        assertTrue(Files.readString(persisted).contains("@org.jsolv.StaticTag"));
        var timestamp = Files.getLastModifiedTime(persisted);
        LabelGenerator.generate(file, output);
        assertEquals(timestamp, Files.getLastModifiedTime(persisted));
        Path manual = output.resolve("Manual.java");
        Files.writeString(manual, "class Manual {}\n");
        LabelGenerator.generate(config("package=app.other\nlabels=Authenticated"), output);
        assertFalse(Files.exists(persisted));
        assertTrue(Files.exists(output.resolve("app/other/Authenticated.java")));
        assertTrue(Files.exists(manual));
    }

    @Test void rejectsMalformedConfigurationBeforeChangingOutput() throws Exception {
        Path output = temporary.resolve("generated");
        LabelGenerator.generate(config("package=app.tags\nlabels=Persisted"), output);
        for (String bad : new String[] {
                "package=app.tags\nlabels=Persisted,Persisted",
                "package=app.tags\nlabels=Persisted,,Validated",
                "package=app.tags\nlabels=Persisted,",
                "package=../escape\nlabels=Validated",
                "package=app.tags\nlabels=class",
                "package=app.tags\nlabels=record",
                "package=app.tags\nlabels=Good\nlabls=Other",
                "package=app.tags\nlabels=Good\nlabels=Other",
                "package=app.tags\nlabels=",
                "labels=Good"}) {
            Path file = config(bad);
            assertThrows(IllegalArgumentException.class, () -> LabelGenerator.generate(file, output), bad);
            assertTrue(Files.exists(output.resolve("app/tags/Persisted.java")));
        }
    }

    @Test void refusesToOverwriteManualAnnotation() throws Exception {
        Path output = temporary.resolve("generated");
        Files.createDirectories(output.resolve("app/tags"));
        Path manual = output.resolve("app/tags/Validated.java");
        Files.writeString(manual, "// manually maintained\n");
        Path file = config("package=app.tags\nlabels=Validated");
        assertThrows(IllegalArgumentException.class, () -> LabelGenerator.generate(file, output));
        assertEquals("// manually maintained\n", Files.readString(manual));
    }
}
