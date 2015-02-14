package uk.co.thefishlive;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

public abstract class AssetMojoProperties extends AbstractMojo {
    /**
     * The hash method to use on the files.
     */
    @Parameter( defaultValue = "sha1", property = "hashMethod", required = true)
    protected String hashMethod;

    /**
     * The id to save the assets under
     */
    @Parameter( defaultValue = "${project.artifactId}-${project.version}", property = "assetId", required = true)
    protected String assetId;

    /**
     * The sub directory to store the assets in
     */
    @Parameter( defaultValue = "data", property = "dataDir", required = true )
    protected String dataDir;

    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${project.build.directory}/assets/", property = "outputDir", required = true )
    protected File outputDirectory;
}
