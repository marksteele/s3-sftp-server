package com.dataexchange.server.conf;

import com.dataexchange.server.aws.CustomAmazonS3ClientFactory;
import com.dataexchange.server.aws.CustomAssumeRoleSessionCredentialsProvider;
import com.dataexchange.server.domain.UserService;
import com.dataexchange.server.sshd.TrackingSftpEventListener;
import com.dataexchange.server.sshd.UserPublicKeyAuthenticator;
import com.dataexchange.server.sshd.file.UserRootedFileSystemFactory;
import com.dataexchange.server.sshd.util.AuthorizedKeysUtils;
import com.google.common.collect.ImmutableMap;
import com.upplication.s3fs.S3FileSystemProvider;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.apache.sshd.server.subsystem.sftp.UnsupportedAttributePolicy;
import org.hibernate.validator.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import javax.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.*;

@Configuration
@EnableConfigurationProperties(SftpServer.SftpServerConfiguration.class)
public class SftpServer {

    private boolean awsStorage = false;

    @Value("${app.sftp.aws.bucket-name}")
    private String bucketName;

    @Autowired
    private SftpServerConfiguration properties;

    @Autowired
    private UserService userService;

    private Path sftpHomeDir;

    @Bean
    public FileSystemFactory userRootedFileSystemFactory() throws IOException, URISyntaxException {
        if (awsStorage) {
            sftpHomeDir = getS3BucketPath();
        } else {
            sftpHomeDir = FileSystems.getDefault().getPath(properties.getBaseFolder());
        }

        return new UserRootedFileSystemFactory(sftpHomeDir);
    }

    @Bean
    public SshServer sshServer(PasswordAuthenticator passwordAuthenticator, TrackingSftpEventListener sftpEventListener)
            throws IOException, URISyntaxException {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(properties.getPort());
        sshServer.setShellFactory(new ProcessShellFactory("/bin/sh", "-i", "-l"));
        sshServer.setCommandFactory(new ScpCommandFactory());

        SftpSubsystemFactory sftpSubsystemFactory = new SftpSubsystemFactory();
        if (awsStorage) {
            // For AWS storage to ignore S3FileSystemProvider.setAttribute
            sftpSubsystemFactory.setUnsupportedAttributePolicy(UnsupportedAttributePolicy.Ignore);
        }
        sftpSubsystemFactory.addSftpEventListener(sftpEventListener);
        sshServer.setSubsystemFactories(Collections.singletonList(sftpSubsystemFactory));

        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(sftpHomeDir.resolve("_key")));
        sshServer.setPasswordAuthenticator(passwordAuthenticator);
        sshServer.setPublickeyAuthenticator(configurePublicKeyAuthenticator(properties.getUsers()));
        sshServer.setFileSystemFactory(userRootedFileSystemFactory());

        sshServer.start();

        return sshServer;
    }

    @ConditionalOnProperty(value = "app.sftp.aws.access-key")
    @Bean
    public CustomAssumeRoleSessionCredentialsProvider assumeRoleSessionCredentialsProvider(@Value("${app.sftp.aws.access-key}") String accessKey,
                                                                                           @Value("${app.sftp.aws.secret-key}") String secretKey,
                                                                                           @Value("${app.sftp.aws.assume-role}") String assumeRole) {
        return new CustomAssumeRoleSessionCredentialsProvider(accessKey, secretKey, assumeRole);
    }

    private Path getS3BucketPath() throws URISyntaxException, IOException {
        Map<String, ?> env = ImmutableMap.<String, Object>builder()
                .put(S3FileSystemProvider.AMAZON_S3_FACTORY_CLASS, CustomAmazonS3ClientFactory.class.getName()).build();

        FileSystem fileSystem = FileSystems.newFileSystem(new URI("s3:///"), env, Thread.currentThread().getContextClassLoader());

        return fileSystem.getPath("/" + bucketName);
    }

    private PublickeyAuthenticator configurePublicKeyAuthenticator(List<SftpUserConfiguration> usersConf) {
        Map<String, Collection<PublicKey>> usersKeyMap = new HashMap<>();
        for (SftpUserConfiguration userConf : usersConf) {
            if (StringUtils.hasText(userConf.getPublicKey())) {
                Collection<PublicKey> keys = usersKeyMap.getOrDefault(userConf.getUsername(), new HashSet<>());
                try {
                    keys.add(AuthorizedKeysUtils.decodePublicKey(userConf.getPublicKey()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                usersKeyMap.putIfAbsent(userConf.getUsername(), keys);
            }
        }

        return new UserPublicKeyAuthenticator(usersKeyMap, userService);
    }

    @ConfigurationProperties(prefix = "app.sftp")
    public static class SftpServerConfiguration {

        private int port = 22;
        
        private String baseFolder; // FIXME: Not used for now
        private Resource publicKeyFile; // FIXME: Not used for now
        @Valid
        private List<SftpUserConfiguration> users = new ArrayList<>();

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getBaseFolder() {
            return baseFolder;
        }

        public void setBaseFolder(String baseFolder) {
            this.baseFolder = baseFolder;
        }

        public Resource getPublicKeyFile() {
            return publicKeyFile;
        }

        public void setPublicKeyFile(Resource publicKeyFile) {
            this.publicKeyFile = publicKeyFile;
        }

        public List<SftpUserConfiguration> getUsers() {
            return users;
        }

        public void setUsers(List<SftpUserConfiguration> users) {
            this.users = users;
        }
    }

    public static class SftpUserConfiguration {

        @NotBlank
        private String username;
        private String publicKey;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
