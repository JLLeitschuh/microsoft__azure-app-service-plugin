/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package org.jenkinsci.plugins.microsoft.appservice.commands;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import com.google.common.io.Files;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.StreamBuildListener;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * Created by juniwang on 26/06/2017.
 */
public class DockerBuildCommandTest extends AbstractDockerCommandTest {
    DockerBuildCommand command;
    DockerBuildCommand.IDockerBuildCommandData commandData;
    FilePath workspace;
    TemporaryFolder dockerfileDir;
    List<File> dockerfiles;
    DockerClient dockerClient;


    private void createTestDockerfile(int n) throws Exception {
        dockerfiles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            File subDir = dockerfileDir.newFolder();
            File dockerfile = new File(subDir, "Dockerfile");
            FileUtils.write(dockerfile, "someContent");
            dockerfiles.add(dockerfile);
        }
    }

    @Before
    public void setup() throws Exception {
        commandData = mock(DockerBuildCommand.IDockerBuildCommandData.class);
        command = spy(new DockerBuildCommand());

        // Create workspace
        File workspaceDir = Files.createTempDir();
        workspaceDir.deleteOnExit();
        workspace = new FilePath(workspaceDir);
        dockerfileDir = new TemporaryFolder(workspaceDir);
        dockerfileDir.create();

        // Mock build
        final AbstractBuild build = mock(AbstractBuild.class);
        when(build.getWorkspace()).thenReturn(workspace);
        when(commandData.getBuild()).thenReturn(build);

        // Mock build listener
        final BuildListener listener = new StreamBuildListener(System.out, Charset.defaultCharset());
        when(commandData.getListener()).thenReturn(listener);

        // Mock docker client
//        dockerClient = spy(command.getDockerClient(defaultExampleAuthConfig()));
        dockerClient = mock(DockerClient.class);
    }

    @Test
    public void noDockerfileTest() throws Exception {
        when(commandData.getDockerBuildInfo()).thenReturn(defaultExampleBuildInfo());
        command.execute(commandData);
        verify(commandData, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void multipleDockerfileTest() throws Exception {
        when(commandData.getDockerBuildInfo()).thenReturn(defaultExampleBuildInfo());
        createTestDockerfile(3);
        command.execute(commandData);
        verify(commandData, times(1)).setDeploymentState(DeploymentState.HasError);
    }

    @Test
    public void dockerBuildCmdTest() throws Exception {
        when(commandData.getDockerBuildInfo()).thenReturn(defaultExampleBuildInfo());
        when(command.getDockerClient(defaultExampleAuthConfig())).thenReturn(dockerClient);
        createTestDockerfile(1);

        BuildImageCmd buildImageCmd = mock(BuildImageCmd.class);
        when(dockerClient.buildImageCmd(any(File.class))).thenReturn(buildImageCmd);
        when(buildImageCmd.withTags(any(Set.class))).thenReturn(buildImageCmd);
        BuildImageResultCallback callback = mock(BuildImageResultCallback.class);
        when(buildImageCmd.exec(any(BuildImageResultCallback.class))).thenReturn(callback);
        when(callback.awaitCompletion()).thenReturn(callback);

        command.execute(commandData);

        verify(dockerClient, times(1)).buildImageCmd(any(File.class));
        verify(buildImageCmd, times(1)).withTags(any(Set.class));
        verify(buildImageCmd, times(1)).exec(any(BuildImageResultCallback.class));
        verify(callback, times(1)).awaitCompletion();
        verify(commandData, times(1)).setDeploymentState(DeploymentState.Success);
    }
}
