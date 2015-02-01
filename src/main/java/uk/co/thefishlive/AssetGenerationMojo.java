package uk.co.thefishlive;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Mojo( name = "generate-assets", defaultPhase = LifecyclePhase.GENERATE_RESOURCES )
public class AssetGenerationMojo extends AbstractMojo {

    private static final Pattern REPLACE_SEPARATOR = Pattern.compile(File.separator, Pattern.LITERAL);
    public static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    /**
     * The hash method to use on the files.
     */
    @Parameter( defaultValue = "sha1", property = "hashMethod", required = true)
    private String hashMethod;

    /**
     * The id to save the assets under
     */
    @Parameter( defaultValue = "${project.artifactId}-${project.version}", property = "assetId", required = true)
    private String assetId;

    /**
     * The sub directory to store the assets in
     */
    @Parameter( defaultValue = "data", property = "dataDir", required = true )
    private String dataDir;

    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${project.build.directory}/assets/", property = "outputDir", required = true )
    private File outputDirectory;

    /**
     * Location of the build directory
     */
    @Parameter( defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File buildDirectory;

    @Component
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    private JsonArray assets = new JsonArray();

    public void execute() throws MojoExecutionException {
        List resources = project.getResources();
        getLog().info("Generating assets for project " + project.getName());

        if (outputDirectory.exists()) {
            try {
                removeRecursive(outputDirectory.toPath());
            } catch (IOException e) {
                throw new MojoExecutionException("Could not remove output directory");
            }
        }

        for (Object object : resources) {
            Resource resource = (Resource) object;

            File dir = new File(resource.getDirectory());
            getLog().info("Processing directory: " + dir.toString());

            try {
                processDir(dir.listFiles(), dir);
            } catch (IOException e) {
                throw new MojoExecutionException("Error hashing files", e);
            }
        }

        try {
            File indexFile = new File(this.outputDirectory, "index.json");
            getLog().info("Generating index file");
            getLog().debug("Index File: " + indexFile.toString());
            getLog().debug("Asset Id: " + this.assetId);

            // Create index file data
            JsonObject index = new JsonObject();
            index.addProperty("id", this.assetId);
            index.addProperty("generated", DATE_FORMAT.format(new Date()));
            index.addProperty("basedir", this.dataDir);
            index.add("assets", this.assets);

            // Write index to disk
            try (FileWriter writer = new FileWriter(indexFile) ) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(index, writer);
            }

            // Write hash file for index
            HashCode hashCode = Files.hash(indexFile, getHashMethod());
            String hash = BaseEncoding.base16().encode(hashCode.asBytes());

            try (FileWriter writer = new FileWriter(new File(indexFile + "." + this.hashMethod))) {
                writer.write(hash);
            }

            // Create zip of assets
            File destFile = new File(buildDirectory, project.getArtifactId() + "-" + project.getVersion() + "-assets.zip");
            getLog().info("Zipping assets");
            getLog().debug("Zip File: " + destFile.toString());
            createZip(outputDirectory, destFile);
            projectHelper.attachArtifact(project, destFile, "assets");
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating index", e);
        }
    }

    private void processDir(File[] dir, File basedir) throws IOException {
        for (File cur : dir) {
            getLog().debug(cur.toString());

            // Recurse through subdirectories
            if (cur.isDirectory()) {
                processDir(cur.listFiles(), basedir);
                continue;
            }

            // Create the hash code for this file
            HashCode hashCode = Files.hash(cur, getHashMethod());
            String hash = BaseEncoding.base16().encode(hashCode.asBytes());
            getLog().debug(hash);

            String path = cur.getAbsolutePath().substring(basedir.getAbsolutePath().length() + 1);
            Matcher matcher = REPLACE_SEPARATOR.matcher(path);
            path = matcher.replaceAll("/");

            assert hash != null; // Not sure if this can be null, but the warning was annoying me
            File output = new File(outputDirectory, this.dataDir + File.separator + hash.substring(0, 1) + File.separator +  hash.substring(1, 2) + File.separator + hash);
            getLog().debug(output.toString());

            // Make sure file exists to write to
            if (!output.getParentFile().exists() && !output.getParentFile().mkdirs()) throw new IOException("Could not create parent file (" + output.getParent() + ")");

            if (output.exists()) {
                getLog().info(String.format("Asset for %s already exists %s, are they the same file?", path, hash));
            } else {
                if (!output.createNewFile()) throw new IOException("Could not create (" + output + ")");
                Files.copy(cur, output);
            }

            JsonObject json = new JsonObject();
            json.addProperty("path", path);
            json.addProperty("hash", hash);
            assets.add(json);

            getLog().debug(json.toString());
            getLog().debug("");
        }
    }

    public HashFunction getHashMethod() {
        switch (this.hashMethod) {
            case "md5":
                return Hashing.md5();
            case "sha256":
                return Hashing.sha256();
            case "sha512":
                return Hashing.sha512();
            case "sha1":
            default:
                return Hashing.sha1();
        }
    }

    public static void removeRecursive(Path path) throws IOException {
        java.nio.file.Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                java.nio.file.Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                // try to delete the file anyway, even if its attributes
                // could not be read, since delete-only access is
                // theoretically possible
                java.nio.file.Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc == null) {
                    java.nio.file.Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    // directory iteration failed; propagate exception
                    throw exc;
                }
            }
        });
    }

    public void createZip(final File dir, File destFile) throws IOException {
        if (destFile.exists() && !destFile.delete()) throw new IOException("Could not delete destination file");
        FileOutputStream dest = new FileOutputStream(destFile);

        try (final ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(dest))){
            java.nio.file.Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    getLog().debug(file.toString());

                    output.putNextEntry(new ZipEntry(file.toString().substring(dir.getAbsolutePath().length() + 1)));
                    Files.copy(file.toFile(), output);
                    output.flush();
                    output.closeEntry();

                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

}
