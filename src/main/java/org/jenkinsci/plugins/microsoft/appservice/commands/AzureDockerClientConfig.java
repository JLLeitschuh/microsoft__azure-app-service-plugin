/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package org.jenkinsci.plugins.microsoft.appservice.commands;

import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AuthConfigurations;
import com.github.dockerjava.core.*;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang.BooleanUtils.isTrue;

/**
 * Created by juniwang on 19/06/2017.
 */
public class AzureDockerClientConfig implements DockerClientConfig, Serializable {
    private static final long serialVersionUID = 1L;

    public static final String DOCKER_HOST = "DOCKER_HOST";

    public static final String DOCKER_TLS_VERIFY = "DOCKER_TLS_VERIFY";

    public static final String DOCKER_CONFIG = "DOCKER_CONFIG";

    public static final String DOCKER_CERT_PATH = "DOCKER_CERT_PATH";

    public static final String API_VERSION = "api.version";

    public static final String REGISTRY_USERNAME = "registry.username";

    public static final String REGISTRY_PASSWORD = "registry.password";

    public static final String REGISTRY_EMAIL = "registry.email";

    public static final String REGISTRY_URL = "registry.url";

    private static final String DOCKER_JAVA_PROPERTIES = "docker-java.properties";

    private static final String DOCKER_CFG = ".dockercfg";

    private static final String CONFIG_JSON = "config.json";

    private static final Set<String> CONFIG_KEYS = new HashSet<String>();

    static {
        CONFIG_KEYS.add(DOCKER_HOST);
        CONFIG_KEYS.add(DOCKER_TLS_VERIFY);
        CONFIG_KEYS.add(DOCKER_CONFIG);
        CONFIG_KEYS.add(DOCKER_CERT_PATH);
        CONFIG_KEYS.add(API_VERSION);
        CONFIG_KEYS.add(REGISTRY_USERNAME);
        CONFIG_KEYS.add(REGISTRY_PASSWORD);
        CONFIG_KEYS.add(REGISTRY_EMAIL);
        CONFIG_KEYS.add(REGISTRY_URL);
    }

    private final URI dockerHost;

    private final String registryUsername, registryPassword, registryEmail, registryUrl, dockerConfig;

    private final SSLConfig sslConfig;

    private final RemoteApiVersion apiVersion;

    AzureDockerClientConfig(URI dockerHost, String dockerConfig, String apiVersion, String registryUrl,
                            String registryUsername, String registryPassword, String registryEmail, SSLConfig sslConfig) {
        this.dockerHost = checkDockerHostScheme(dockerHost);
        this.dockerConfig = dockerConfig;
        this.apiVersion = RemoteApiVersion.parseConfigWithDefault(apiVersion);
        this.sslConfig = sslConfig;
        this.registryUsername = registryUsername;
        this.registryPassword = registryPassword;
        this.registryEmail = registryEmail;
        this.registryUrl = registryUrl;
    }

    private URI checkDockerHostScheme(URI dockerHost) {
        if ("tcp".equals(dockerHost.getScheme()) || "unix".equals(dockerHost.getScheme())) {
            return dockerHost;
        } else {
            throw new DockerClientException("Unsupported protocol scheme found: '" + dockerHost
                    + "'. Only 'tcp://' or 'unix://' supported.");
        }
    }

    private static Properties loadIncludedDockerProperties(Properties systemProperties) {
        try (InputStream is = DefaultDockerClientConfig.class.getResourceAsStream("/" + DOCKER_JAVA_PROPERTIES)) {
            Properties p = new Properties();
            p.load(is);
            replaceProperties(p, systemProperties);
            return p;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void replaceProperties(Properties properties, Properties replacements) {
        for (Object objectKey : properties.keySet()) {
            String key = objectKey.toString();
            properties.setProperty(key, replaceProperties(properties.getProperty(key), replacements));
        }
    }

    private static String replaceProperties(String s, Properties replacements) {
        for (Map.Entry<Object, Object> entry : replacements.entrySet()) {
            String key = "${" + entry.getKey() + "}";
            while (s.contains(key)) {
                s = s.replace(key, String.valueOf(entry.getValue()));
            }
        }
        return s;
    }

    /**
     * Creates a new Properties object containing values overridden from ${user.home}/.docker.io.properties
     *
     * @param p The original set of properties to override
     * @return A copy of the original Properties with overridden values
     */
    private static Properties overrideDockerPropertiesWithSettingsFromUserHome(Properties p, Properties systemProperties) {
        Properties overriddenProperties = new Properties();
        overriddenProperties.putAll(p);

        final File usersDockerPropertiesFile = new File(systemProperties.getProperty("user.home"),
                "." + DOCKER_JAVA_PROPERTIES);
        if (usersDockerPropertiesFile.isFile()) {
            try (FileInputStream in = new FileInputStream(usersDockerPropertiesFile)) {
                overriddenProperties.load(in);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return overriddenProperties;
    }

    private static Properties overrideDockerPropertiesWithEnv(Properties properties, Map<String, String> env) {
        Properties overriddenProperties = new Properties();
        overriddenProperties.putAll(properties);

        // special case which is a sensible default
        if (env.containsKey(DOCKER_HOST)) {
            overriddenProperties.setProperty(DOCKER_HOST, env.get(DOCKER_HOST));
        }

        for (Map.Entry<String, String> envEntry : env.entrySet()) {
            String envKey = envEntry.getKey();
            if (CONFIG_KEYS.contains(envKey)) {
                overriddenProperties.setProperty(envKey, envEntry.getValue());
            }
        }

        return overriddenProperties;
    }

    /**
     * Creates a new Properties object containing values overridden from the System properties
     *
     * @param p The original set of properties to override
     * @return A copy of the original Properties with overridden values
     */
    private static Properties overrideDockerPropertiesWithSystemProperties(Properties p, Properties systemProperties) {
        Properties overriddenProperties = new Properties();
        overriddenProperties.putAll(p);

        for (String key : CONFIG_KEYS) {
            if (systemProperties.containsKey(key)) {
                overriddenProperties.setProperty(key, systemProperties.getProperty(key));
            }
        }
        return overriddenProperties;
    }

    public static AzureDockerClientConfig.Builder createDefaultConfigBuilder() {
        return createDefaultConfigBuilder(System.getenv(), (Properties) System.getProperties().clone());
    }

    /**
     * Allows you to build the config without system environment interfering for more robust testing
     */
    static AzureDockerClientConfig.Builder createDefaultConfigBuilder(Map<String, String> env, Properties systemProperties) {
        Properties properties = loadIncludedDockerProperties(systemProperties);
        properties = overrideDockerPropertiesWithSettingsFromUserHome(properties, systemProperties);
        properties = overrideDockerPropertiesWithEnv(properties, env);
        properties = overrideDockerPropertiesWithSystemProperties(properties, systemProperties);
        return new AzureDockerClientConfig.Builder().withProperties(properties);
    }

    @Override
    public URI getDockerHost() {
        return dockerHost;
    }

    @Override
    public RemoteApiVersion getApiVersion() {
        return apiVersion;
    }

    @Override
    public String getRegistryUsername() {
        return registryUsername;
    }

    @Override
    public String getRegistryPassword() {
        return registryPassword;
    }

    @Override
    public String getRegistryEmail() {
        return registryEmail;
    }

    @Override
    public String getRegistryUrl() {
        return registryUrl;
    }

    public String getDockerConfig() {
        return dockerConfig;
    }

    private AuthConfig getAuthConfig() {
        AuthConfig authConfig = null;
        if (getRegistryUsername() != null && getRegistryPassword() != null && getRegistryUrl() != null) {
            authConfig = new AuthConfig()
                    .withUsername(getRegistryUsername())
                    .withPassword(getRegistryPassword())
                    .withEmail(getRegistryEmail())
                    .withRegistryAddress(getRegistryUrl());
        }
        return authConfig;
    }

    @Override
    public AuthConfig effectiveAuthConfig(String imageName) {
        return getAuthConfig();
    }

    @Override
    public AuthConfigurations getAuthConfigurations() {
        final AuthConfigurations authConfigurations = new AuthConfigurations();
        authConfigurations.addConfig(getAuthConfig());
        return authConfigurations;
    }

    @Override
    public SSLConfig getSSLConfig() {
        return sslConfig;
    }

    @Override
    public boolean equals(Object o) {
        return EqualsBuilder.reflectionEquals(this, o);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this, ToStringStyle.SHORT_PREFIX_STYLE);
    }

    public static class Builder {
        private URI dockerHost;

        private String apiVersion, registryUsername, registryPassword, registryEmail, registryUrl, dockerConfig,
                dockerCertPath;

        private Boolean dockerTlsVerify;

        private SSLConfig customSslConfig = null;

        /**
         * This will set all fields in the builder to those contained in the Properties object. The Properties object should contain the
         * following docker-java configuration keys: DOCKER_HOST, DOCKER_TLS_VERIFY, api.version, registry.username, registry.password,
         * registry.email, DOCKER_CERT_PATH, and DOCKER_CONFIG.
         */
        public AzureDockerClientConfig.Builder withProperties(Properties p) {
            return withDockerHost(p.getProperty(DOCKER_HOST))
                    .withDockerTlsVerify(p.getProperty(DOCKER_TLS_VERIFY))
                    .withDockerConfig(p.getProperty(DOCKER_CONFIG))
                    .withDockerCertPath(p.getProperty(DOCKER_CERT_PATH))
                    .withApiVersion(p.getProperty(API_VERSION))
                    .withRegistryUsername(p.getProperty(REGISTRY_USERNAME))
                    .withRegistryPassword(p.getProperty(REGISTRY_PASSWORD))
                    .withRegistryEmail(p.getProperty(REGISTRY_EMAIL))
                    .withRegistryUrl(p.getProperty(REGISTRY_URL));
        }

        /**
         * configure DOCKER_HOST
         */
        public final AzureDockerClientConfig.Builder withDockerHost(String dockerHost) {
            checkNotNull(dockerHost, "uri was not specified");
            this.dockerHost = URI.create(dockerHost);
            return this;
        }

        public final AzureDockerClientConfig.Builder withApiVersion(RemoteApiVersion apiVersion) {
            this.apiVersion = apiVersion.getVersion();
            return this;
        }

        public final AzureDockerClientConfig.Builder withApiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        public final AzureDockerClientConfig.Builder withRegistryUsername(String registryUsername) {
            this.registryUsername = registryUsername;
            return this;
        }

        public final AzureDockerClientConfig.Builder withRegistryPassword(String registryPassword) {
            this.registryPassword = registryPassword;
            return this;
        }

        public final AzureDockerClientConfig.Builder withRegistryEmail(String registryEmail) {
            this.registryEmail = registryEmail;
            return this;
        }

        public AzureDockerClientConfig.Builder withRegistryUrl(String registryUrl) {
            this.registryUrl = registryUrl;
            return this;
        }

        public final AzureDockerClientConfig.Builder withDockerCertPath(String dockerCertPath) {
            this.dockerCertPath = dockerCertPath;
            return this;
        }

        public final AzureDockerClientConfig.Builder withDockerConfig(String dockerConfig) {
            this.dockerConfig = dockerConfig;
            return this;
        }

        public final AzureDockerClientConfig.Builder withDockerTlsVerify(String dockerTlsVerify) {
            if (dockerTlsVerify != null) {
                String trimmed = dockerTlsVerify.trim();
                this.dockerTlsVerify = "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed);
            } else {
                this.dockerTlsVerify = false;
            }
            return this;
        }

        public final AzureDockerClientConfig.Builder withDockerTlsVerify(Boolean dockerTlsVerify) {
            this.dockerTlsVerify = dockerTlsVerify;
            return this;
        }

        /**
         * Overrides the default {@link SSLConfig} that is used when calling {@link DefaultDockerClientConfig.Builder#withDockerTlsVerify(java.lang.Boolean)} and
         * {@link DefaultDockerClientConfig.Builder#withDockerCertPath(String)}. This way it is possible to pass a custom {@link SSLConfig} to the resulting
         * {@link DockerClientConfig} that may be created by other means than the local file system.
         */
        public final AzureDockerClientConfig.Builder withCustomSslConfig(SSLConfig customSslConfig) {
            this.customSslConfig = customSslConfig;
            return this;
        }

        public AzureDockerClientConfig build() {

            SSLConfig sslConfig = null;

            if (customSslConfig == null) {
                if (isTrue(dockerTlsVerify)) {
                    dockerCertPath = checkDockerCertPath(dockerCertPath);
                    sslConfig = new LocalDirectorySSLConfig(dockerCertPath);
                }
            } else {
                sslConfig = customSslConfig;
            }

            return new AzureDockerClientConfig(dockerHost, dockerConfig, apiVersion, registryUrl, registryUsername,
                    registryPassword, registryEmail, sslConfig);
        }

        private String checkDockerCertPath(String dockerCertPath) {
            if (StringUtils.isEmpty(dockerCertPath)) {
                throw new DockerClientException(
                        "Enabled TLS verification (DOCKER_TLS_VERIFY=1) but certifate path (DOCKER_CERT_PATH) is not defined.");
            }

            File certPath = new File(dockerCertPath);

            if (!certPath.exists()) {
                throw new DockerClientException(
                        "Enabled TLS verification (DOCKER_TLS_VERIFY=1) but certificate path (DOCKER_CERT_PATH) '"
                                + dockerCertPath + "' doesn't exist.");
            } else if (!certPath.isDirectory()) {
                throw new DockerClientException(
                        "Enabled TLS verification (DOCKER_TLS_VERIFY=1) but certificate path (DOCKER_CERT_PATH) '"
                                + dockerCertPath + "' doesn't point to a directory.");
            }

            return dockerCertPath;
        }
    }
}

