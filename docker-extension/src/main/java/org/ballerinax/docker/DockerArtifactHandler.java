/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinax.docker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.docker.api.model.AuthConfig;
import io.fabric8.docker.api.model.AuthConfigBuilder;
import io.fabric8.docker.client.Config;
import io.fabric8.docker.client.ConfigBuilder;
import io.fabric8.docker.client.DefaultDockerClient;
import io.fabric8.docker.client.DockerClient;
import io.fabric8.docker.client.utils.RegistryUtils;
import io.fabric8.docker.dsl.EventListener;
import io.fabric8.docker.dsl.OutputHandle;
import org.ballerinax.docker.exceptions.DockerPluginException;
import org.ballerinax.docker.models.DockerModel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import static org.ballerinax.docker.utils.DockerGenUtils.isBlank;
import static org.ballerinax.docker.utils.DockerGenUtils.printDebug;

/**
 * Generates Docker artifacts from annotations.
 */
public class DockerArtifactHandler {

    private final CountDownLatch pushDone = new CountDownLatch(1);
    private final CountDownLatch buildDone = new CountDownLatch(1);
    private DockerModel dockerModel;

    public DockerArtifactHandler(DockerModel dockerModel) {
        this.dockerModel = dockerModel;
        if (!isBlank(dockerModel.getDockerCertPath())) {
            System.setProperty("docker.cert.path", dockerModel.getDockerCertPath());
        }
    }

    private static void disableFailOnUnknownProperties() {
        // Disable fail on unknown properties using reflection to avoid docker client issue.
        // (https://github.com/fabric8io/docker-client/issues/106).
        final Field jsonMapperField;
        try {
            jsonMapperField = Config.class.getDeclaredField("JSON_MAPPER");
            assert jsonMapperField != null;
            jsonMapperField.setAccessible(true);
            final ObjectMapper objectMapper = (ObjectMapper) jsonMapperField.get(null);
            assert objectMapper != null;
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
    }

    /**
     * Create docker image.
     *
     * @param dockerModel dockerModel object
     * @param dockerDir   dockerfile directory
     * @throws InterruptedException When error with docker build process
     * @throws IOException          When error with docker build process
     */
    public void buildImage(DockerModel dockerModel, String dockerDir) throws
            InterruptedException, IOException, DockerPluginException {
        disableFailOnUnknownProperties();
        Config dockerClientConfig = new ConfigBuilder()
                .withDockerUrl(dockerModel.getDockerHost())
                .build();
        DockerClient client = new io.fabric8.docker.client.DefaultDockerClient(dockerClientConfig);
        final DockerError dockerError = new DockerError();
        OutputHandle buildHandle = client.image()
                .build()
                .withRepositoryName(dockerModel.getName())
                .withNoCache()
                .alwaysRemovingIntermediate()
                .usingListener(new EventListener() {
                    @Override
                    public void onSuccess(String message) {
                        buildDone.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        dockerError.setErrorMsg("Unable to build Docker image: " + message);
                        buildDone.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        dockerError.setErrorMsg("Unable to build Docker image: " + t.getMessage());
                        buildDone.countDown();
                    }

                    @Override
                    public void onEvent(String event) {
                        printDebug(event);
                    }
                })
                .fromFolder(dockerDir);
        buildDone.await();
        buildHandle.close();
        client.close();
        handleError(dockerError);
    }

    private void handleError(DockerError dockerError) throws DockerPluginException {
        if (dockerError.isError()) {
            throw new DockerPluginException(dockerError.getErrorMsg());
        }
    }

    /**
     * Push docker image.
     *
     * @param dockerModel DockerModel
     * @throws InterruptedException When error with docker build process
     * @throws IOException          When error with docker build process
     */
    public void pushImage(DockerModel dockerModel) throws InterruptedException, IOException, DockerPluginException {
        disableFailOnUnknownProperties();
        AuthConfig authConfig = new AuthConfigBuilder().withUsername(dockerModel.getUsername()).withPassword
                (dockerModel.getPassword())
                .build();
        Config config = new ConfigBuilder()
                .withDockerUrl(dockerModel.getDockerHost())
                .addToAuthConfigs(RegistryUtils.extractRegistry(dockerModel.getName()), authConfig)
                .build();

        DockerClient client = new DefaultDockerClient(config);
        final DockerError dockerError = new DockerError();
        OutputHandle handle = client.image().withName(dockerModel.getName()).push()
                .usingListener(new EventListener() {
                    @Override
                    public void onSuccess(String message) {
                        pushDone.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        pushDone.countDown();
                        dockerError.setErrorMsg("Unable to push Docker image: " + message);
                    }

                    @Override
                    public void onError(Throwable t) {
                        pushDone.countDown();
                        dockerError.setErrorMsg("Unable to push Docker image: " + t.getMessage());
                    }

                    @Override
                    public void onEvent(String event) {
                        printDebug(event);
                    }
                })
                .toRegistry();

        pushDone.await();
        handle.close();
        client.close();
        handleError(dockerError);
    }

    /**
     * Generate Dockerfile content.
     *
     * @return Dockerfile content as a string
     */
    public String generate() {
        String dockerBase = "# Auto Generated Dockerfile\n" +
                "\n" +
                "FROM " + dockerModel.getBaseImage() + "\n" +
                "LABEL maintainer=\"dev@ballerina.io\"\n" +
                "\n" +
                "COPY " + dockerModel.getBalxFileName() + " /home/ballerina \n\n";

        StringBuilder stringBuffer = new StringBuilder(dockerBase);
        dockerModel.getFiles().forEach(file -> {
            // Extract the source filename relative to docker folder.
            String sourceFileName = String.valueOf(Paths.get(file.getSource()).getFileName());
            stringBuffer.append("COPY ")
                    .append(sourceFileName)
                    .append(" ")
                    .append(file.getTarget())
                    .append("\n");
        });
        if (dockerModel.isService() && dockerModel.getPorts().size() > 0) {
            stringBuffer.append("EXPOSE ");
            dockerModel.getPorts().forEach(port -> stringBuffer.append(" ").append(port));
            stringBuffer.append("\n\nCMD ballerina run ");
        } else {
            stringBuffer.append("CMD ballerina run ");
        }
        dockerModel.getFiles().forEach(file -> {
            if (file.isBallerinaConf()) {
                stringBuffer.append(" --config ").append(file.getTarget());
            }
        });
        if (dockerModel.isEnableDebug()) {
            stringBuffer.append(" --debug ").append(dockerModel.getDebugPort());
        }
        stringBuffer.append(" ").append(dockerModel.getBalxFileName());
        stringBuffer.append("\n");
        return stringBuffer.toString();
    }

    /**
     * Class to hold docker errors.
     */
    private static class DockerError {
        private boolean error;
        private String errorMsg;

        DockerError() {
            this.error = false;
        }

        boolean isError() {
            return error;
        }

        String getErrorMsg() {
            return errorMsg;
        }

        void setErrorMsg(String errorMsg) {
            this.error = true;
            this.errorMsg = errorMsg;
        }
    }
}
