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

import io.kubernetes.client.extended.kubectl.exception.KubectlException;
import model.kubernetes.exception.ApplyException;
import model.kubernetes.exception.DeploymentDeleteException;
import model.kubernetes.exception.DeploymentNotFoundException;

import java.util.List;

public interface IDeployment {
    String getFileName();
    List<IPod> getPods() throws DeploymentNotFoundException;
    String getName();
    int getDeploymentCounter();
    void setDeploymentCounter(int deploymentCounter);

    /**
     * TODO: this method is probably not needed anymore.
     */
    void trySetCounterFromName(String basename);

    /**
     * Apply the deployment with a UUID string appended to the deployment name.
     *
     * @throws ApplyException
     */
    String applyUnique() throws ApplyException;

    /**
     * Apply the Deployment with an integer at the end of the deployment name.
     * @param deploymentCounter Integer that is appended to the deployment name.
     * @throws ApplyException Throws if there was a problem with applying.
     */
    void apply(int deploymentCounter) throws ApplyException;

    /**
     * Forces a rollout restart of the deployment by patching the running config file in memory with a timestamp.
     * Restart only occurs if the patch is unique, hence why the timestamp is added.
     * @throws ApplyException Throws exception if the change could not be applied.
     */
    void rolloutRestart() throws ApplyException;

    /**
     * Scales replicas
     * @param nReplicas The new number of replicas
     * @throws ApplyException Throws exception if the change could not be applied.
     */
    void scaleReplicas(int nReplicas) throws ApplyException;


    /**
     * Deletes this deployment from the cluster
     * @throws DeploymentDeleteException Throws exception if deployment could not be deleted or does not exist.
     */
    void delete() throws DeploymentDeleteException;

    /**
     * Creates and returns a list of deployments whose name contain this basename.
     *
     * @param namespace the namespace in which to look for deployments.
     * @return a list of related deployments.
     * @throws KubectlException
     * @throws DeploymentNotFoundException
     */
    List<IDeployment> getRelatedDeployments(String namespace) throws KubectlException, DeploymentNotFoundException;
}
