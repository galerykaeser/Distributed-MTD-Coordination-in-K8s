/*
 * Moving Target Defense with Kubernetes
 * Copyright (C) 2022  Philip Tibom and Max Buck
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package model.kubernetes;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.kubectl.Kubectl;
import io.kubernetes.client.extended.kubectl.exception.KubectlException;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Yaml;
import model.kubernetes.exception.ApplyException;
import model.kubernetes.exception.DeploymentDeleteException;
import model.kubernetes.exception.DeploymentNotFoundException;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public class Deployment implements IDeployment {

    private V1Deployment v1Deployment;
    private final V1Deployment v1DeploymentUnmodified;
    private String filename;
    private String baseName;
    private int deploymentCounter = -1;

    /**
     * Creates a Deployment object from a config file
     * @param file A kubernetes deployment.yaml file
     * @throws IOException Throws IOException if file does not exist
     */
    public Deployment(File file) throws IOException {
        filename = file.getName();
        v1Deployment = (V1Deployment) Yaml.load(file);
        v1DeploymentUnmodified = (V1Deployment) Yaml.load(file);
        baseName = v1DeploymentUnmodified.getMetadata().getName();
    }

    /**
     * Gets a Deployment from a running cluster based on the Deployment name.
     * @param name Deployment name that is to be found in the cluster.
     * @param namespace Cluster namespace to look for the deployment
     * @throws DeploymentNotFoundException Throws exception if deployment is not found.
     */
    public Deployment(String name, String namespace) throws DeploymentNotFoundException {
        try {
            v1Deployment = Kubectl.get(V1Deployment.class)
                    .namespace(namespace)
                    .name(name)
                    .execute();
            v1DeploymentUnmodified = null;
        } catch (KubectlException e) {
            throw new DeploymentNotFoundException(e.getMessage());
        }
    }

    /**
     * Gets all pods belonging to this deployment
     * @return List of pods
     * @throws DeploymentNotFoundException Throws exception if Deployment is not found
     */
    public List<IPod> getPods() throws DeploymentNotFoundException {
        CoreV1Api api = new CoreV1Api();
        String label = "app=" + v1Deployment.getMetadata().getLabels().get("app");
        try {
            V1PodList list = api.listNamespacedPod(v1Deployment.getMetadata().getNamespace(),
                    null, null, null, null, label, null, null, null, null, null);
            List<IPod> pods = new ArrayList<>();
            for(V1Pod pod : list.getItems()) {
                pods.add(new Pod(pod));
            }
            return pods;
        } catch (ApiException e) {
            throw new DeploymentNotFoundException(e.getMessage());
        }
    }

    /**
     * Apply the deployment with a UUID string appended to the deployment name.
     *
     * @throws ApplyException
     */
    @Override
    public String applyUnique() throws ApplyException {
        String appendix = UUID.randomUUID().toString();
        v1DeploymentUnmodified.getMetadata().setName(String.format("%s-%s", baseName, appendix));
        try {
            v1Deployment = Kubectl.apply(V1Deployment.class)
                    .resource(v1DeploymentUnmodified)
                    .execute();
        } catch (KubectlException e) {
            throw new ApplyException(e.getMessage());
        }

        return appendix;
    }

    /**
     * Apply the Deployment with an integer at the end of the deployment name.
     * @param deploymentCounter Integer that is appended to the deployment name.
     * @throws ApplyException Throws if there was a problem with applying.
     */
    @Override
    public void apply(int deploymentCounter) throws ApplyException {
        setDeploymentCounter(deploymentCounter);
        v1DeploymentUnmodified.getMetadata().setName(baseName + deploymentCounter);
        try {
            v1Deployment = Kubectl.apply(V1Deployment.class)
                    .resource(v1DeploymentUnmodified)
                    .execute();
        } catch (KubectlException e) {
            throw new ApplyException(e.getMessage());
        }
    }

    /**
     * Scales replicas
     * @param nReplicas The new number of replicas
     * @throws ApplyException Throws exception if the change could not be applied.
     */
    @Override
    public void scaleReplicas(int nReplicas) throws ApplyException {
        v1DeploymentUnmodified.getSpec().setReplicas(nReplicas);
        apply(1);
    }

    /**
     * Forces a rollout restart of the deployment by patching the running config file in memory with a timestamp.
     * Restart only occurs if the patch is unique, hence why the timestamp is added.
     * @throws ApplyException Throws exception if the change could not be applied.
     */
    @Override
    public void rolloutRestart() throws ApplyException {
        // todo The following 3 lines may no longer be necessary.
        Map<String, String> restart = new HashMap<>();
        restart.put("date", Instant.now().getEpochSecond() + "");
        v1Deployment.getSpec().getTemplate().getMetadata().setAnnotations(restart);
        try {
            v1Deployment = Kubectl.patch(V1Deployment.class)
                            .namespace(v1Deployment.getMetadata().getNamespace())
                            .name(v1Deployment.getMetadata().getName())
                            .patchType(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH)
                            .patchContent(new V1Patch("{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"date\":\"" +
                                System.currentTimeMillis() +"\"}}}}}"))
                            .execute();
        } catch (KubectlException e) {
            throw new ApplyException(e.getMessage());
        }
    }

    /**
     * Deletes this deployment from the cluster
     * @throws DeploymentDeleteException Throws exception if deployment could not be deleted or does not exist.
     */
    @Override
    public void delete() throws DeploymentDeleteException {
        try {
            Kubectl.delete(V1Deployment.class)
                    .namespace(v1Deployment.getMetadata().getNamespace())
                    .name(v1Deployment.getMetadata().getName())
                    .execute();
            v1Deployment = null;
        } catch (KubectlException e) {
            throw new DeploymentDeleteException(e.getMessage());
        }
    }

    /**
     * Creates and returns a list of deployments whose name contain this basename.
     *
     * @param namespace the namespace in which to look for deployments.
     * @return a list of related deployments.
     * @throws KubectlException
     * @throws DeploymentNotFoundException
     */
    @Override
    public List<IDeployment> getRelatedDeployments(String namespace) throws KubectlException, DeploymentNotFoundException {
        List<IDeployment> relatedDeployments = new ArrayList<>();
        List<V1Deployment> allDeployments =  Kubectl.get(V1Deployment.class).namespace(namespace).execute();

        for (V1Deployment deployment: allDeployments) {
            String name = deployment.getMetadata().getName();
            if (name.contains(baseName)) {
                IDeployment relatedDeployment = new Deployment(name, namespace);
                relatedDeployments.add(relatedDeployment);
            }
        }

        return relatedDeployments;
    }

    @Override
    public String getName() {
        return v1Deployment.getMetadata().getName();
    }

    @Override
    public int getDeploymentCounter() {
        return deploymentCounter;
    }

    @Override
    public void setDeploymentCounter(int deploymentCounter) {
        this.deploymentCounter = deploymentCounter;
    }

    /**
     * TODO: this method is probably not needed anymore.
     */
    @Override
    public void trySetCounterFromName(String baseName) {
        String counterString = getName().replace(baseName, "");
        System.out.println("Deployment name is still " + getName());
        try {
            int extractedDeploymentCounter = Integer.parseInt(counterString);
            setDeploymentCounter(extractedDeploymentCounter);
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public String getFileName() {
        return filename;
    }

}
