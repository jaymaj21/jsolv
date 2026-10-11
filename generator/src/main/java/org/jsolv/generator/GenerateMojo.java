package org.jsolv.generator;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

/** Generates application-owned label annotations before compilation. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateMojo extends AbstractMojo {
    @Parameter(defaultValue = "${basedir}/jsolv.properties", property = "jsolv.config", required = true)
    private File configFile;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/jsolv", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        try {
            int count = LabelGenerator.generate(configFile.toPath(), outputDirectory.toPath());
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().info("Generated " + count + " static tags from " + configFile);
        } catch (IOException | IllegalArgumentException error) {
            throw new MojoExecutionException("Cannot generate static tags: " + error.getMessage(), error);
        }
    }
}
