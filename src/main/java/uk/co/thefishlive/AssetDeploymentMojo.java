package uk.co.thefishlive;

import com.google.common.base.Splitter;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 *
 */
@Mojo( name = "deploy-assets", defaultPhase = LifecyclePhase.DEPLOY, requiresOnline = true )
public class AssetDeploymentMojo extends AbstractAssetMojo {

    @Component
    private MavenProject project;

    @Component
    private Settings settings;

    /**
     * The host to upload the assets to
     */
    @Parameter ( property = "remote.host", required = true )
    private String host;

    /**
     * Server name for the asset deployment to get login details
     */
    @Parameter ( property = "remote.server", required = true )
    private String server;

    private FTPClient client;
    private int count;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        connectToServer();

        try {
            Path path = Paths.get(this.outputDirectory.getAbsolutePath(), this.dataDir);

            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    deploy(file.toFile());
                    count++;
                    return FileVisitResult.CONTINUE;
                }
            });

            getLog().info("Uploaded " + count + " files");
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Error disconnecting from ftp server", e);
        }
    }

    private void deploy(File file) throws IOException {
        String path = file.getAbsolutePath().substring(this.outputDirectory.getAbsolutePath().length() + 1);
        getLog().info("Uploading \"" + path + "\"");

        int count = navToDir(path);

        FileInputStream in = new FileInputStream(file);
        client.storeFile(file.getName(), in);

        for (int i = 0; i < count; i++) {
            client.changeToParentDirectory();
        }
    }

    private int navToDir(String path) throws IOException {
        Splitter splitter = Splitter.on(File.separator).omitEmptyStrings();
        int count = 0;

        for (String sting : splitter.split(path)) {
            if (!client.changeWorkingDirectory(sting)) {
                client.makeDirectory(sting);
                client.changeWorkingDirectory(sting);
            }

            count++;
        }

        return count;
    }

    private void connectToServer() throws MojoExecutionException {
        Server server = settings.getServer(this.server);

        if (server == null) {
            throw new MojoExecutionException("Cannot find login server to use");
        }

        try {
            client = new FTPClient();
            InetAddress host = InetAddress.getByName(this.host);

            client.connect(host);

            int reply = client.getReplyCode();

            if (!FTPReply.isPositiveCompletion(reply)) {
                throw new MojoExecutionException("Error connecting to the ftp server (error code " + reply + ")");
            }

            if (!client.login(server.getUsername(), server.getPassword())) {
                throw new MojoExecutionException("Error logging into ftp server with username " + server.getUsername() + " and password " + (server.getPassword().length() == 0 ? "NO" : "YES"));
            }

            client.setListHiddenFiles(false);
            client.setFileType(FTP.BINARY_FILE_TYPE);
        } catch (UnknownHostException e) {
            throw new MojoExecutionException("Could not find ftp server", e);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not connect to ftp server", e);
        }
    }
}
