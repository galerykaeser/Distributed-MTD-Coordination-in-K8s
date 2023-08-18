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

package controller.algs;

import io.kubernetes.client.extended.kubectl.exception.KubectlException;
import model.kubernetes.*;
import model.kubernetes.exception.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The best working version of our MTD algorithms. Has better handling of the integrated load-balancer than V1 and V2.
 */
public class MtdRandomV3 implements IMtdAlg {

    private int timeBetweenSwap = 5000; // todo Make timeBetweenSwap randomized.
    // Label key for the active k8 node
    private static final String LABEL_KEY = "mtd/node";
    // Label value for the active K8 node.
    private static final String LABEL_VALUE = "active";
    private static final String NAMESPACE = "default";
    private INode currentNode = null;
    private IDeployment currentDeployment = null;
    private List<IDeployment> deployments;


    /**
     * @param deployments     A list of deployments that will be randomized during the MTD execution.
     * @param timeBetweenSwap The time the MTD should wait before swapping to a different node.
     */
    public MtdRandomV3(List<IDeployment> deployments, int timeBetweenSwap) {
        this.timeBetweenSwap = timeBetweenSwap;
        this.deployments = deployments;
    }

    /**
     * Used for running the MTD algorithm forever
     *
     * @return Returns a list of logging events
     */
    @Override
    public List<String> run() {
        return run(0);
    }

    /**
     * Deletes all related deployments except the one with the highest counter.
     * Returns an instance of the deployment with the highest encountered counter.
     * Returns null if no related deployment is encountered.
     * TODO: this method is probably not needed anymore.
     *
     * @return the related deployment with the highest counter, or null
     */
    private IDeployment cleanupPreviousDeployments() {
        IDeployment latestOldDeployment = null;
        int maxCounter = -1; //this is also the value that objects of type Deployment are initialized with
        List<IDeployment> allRelatedDeployments = new ArrayList<>();

        try {
            for (IDeployment deployment : deployments) {
                for (IDeployment relatedDeployment : deployment.getRelatedDeployments(NAMESPACE)) {
                    maxCounter = Math.max(maxCounter, relatedDeployment.getDeploymentCounter());
                    allRelatedDeployments.add(relatedDeployment);
                    if (relatedDeployment.getDeploymentCounter() == maxCounter) {
                        latestOldDeployment = relatedDeployment;
                    }
                }
            }

            //delete all old deployments except the latest
            for (IDeployment relatedDeployment : allRelatedDeployments) {
                if (!relatedDeployment.getName().equals(latestOldDeployment.getName())) {
                    relatedDeployment.delete();
                }
            }
        } catch (DeploymentNotFoundException | KubectlException | DeploymentDeleteException e) {
            throw new RuntimeException(e);
        }

        return latestOldDeployment;
    }

    /**
     * Creates and returns a list of active deployments related to the deployments of this MtdRandomV3 object.
     *
     * @return the created list of deployments.
     */
    private List<IDeployment> getPreviousDeployments() {
        List<IDeployment> previousDeployments = new ArrayList<>();
        List<String> uniqueDeploymentNames = new ArrayList<>();

        try {
            for (IDeployment deployment : deployments) {
                for (IDeployment relatedDeployment: deployment.getRelatedDeployments(NAMESPACE)) {
                    if (!uniqueDeploymentNames.contains(relatedDeployment.getName())) {
                        uniqueDeploymentNames.add(relatedDeployment.getName());
                        previousDeployments.add(relatedDeployment);
                    }
                }
            }
        } catch (DeploymentNotFoundException | KubectlException e) {
            throw new RuntimeException(e);
        }

        return previousDeployments;
    }

    /**
     * Delete "mtd/node" label from every worker node. Return a list of nodes which had a label.
     *
     * @return a list of nodes which had a label prior to the execution of this function.
     */
    private List<INode> cleanUpOldLabels() {
        List<INode> nodesWithLabels = new ArrayList<>();
        try {
            // Delete old labels where they exist.
            for (INode node : NodeTools.getWorkerNodes()) {
                if (node.getLabels().containsKey(LABEL_KEY)) {
                    System.out.println("Deleting label from: " + node.getName());
                    node.deleteLabel(LABEL_KEY);
                    nodesWithLabels.add(node);
                }
            }
        } catch (NodeNotFoundException | NodeLabelException ignored) {
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Not enough worker nodes.");
        }
        return nodesWithLabels;
    }

    /**
     * Performs one target movement. Based on the "run(int nSwaps)" method.
     */
    private void moveTarget() {
        System.out.println("\n============= Moving the target ============");

        List<INode> oldNodes = cleanUpOldLabels();
        List<IDeployment> previousDeployments = getPreviousDeployments();

        try {
            //1. choose new node, ignoring old nodes if possible
            List<INode> nodeList = NodeTools.getWorkerNodes();
            if (oldNodes.size() < nodeList.size()) {
                for (INode oldNode: oldNodes) {
                    System.out.println("Removing " + oldNode.getName() + " from next random selection.");
                    nodeList.removeIf(n -> n.getName().equals(oldNode.getName()));
                }
            }
//            System.out.println(String.format("Old nodes: %s", oldNodes));
//            System.out.println(String.format("Remaining nodes: %s", nodeList));

            // Choose a random node out of the remaining available, then set it to active.
            Random random = new Random();
            int randIntNode = random.nextInt(nodeList.size());
            // Set new current node.
            currentNode = nodeList.get(randIntNode);
            System.out.println("Randomly selected node: " + currentNode.getName() + ", adding active label.");
            currentNode.addLabel(LABEL_KEY, LABEL_VALUE);

            //2. choose deployment randomly and apply it with unique appendix
            // Choose a random deployment, can select same again
            int randIntDeployment = random.nextInt(deployments.size());
            // Swap active deployment
            currentDeployment = deployments.get(randIntDeployment);
            System.out.println("Randomly selected Deployment: " + currentDeployment.getFileName());
            // Start a second pod on the new currentNode
            // Deploy with unique UUID attached to deployment name to distinguish deployments
            String deploymentId = currentDeployment.applyUnique();
            System.out.println("Applying deployment with name: " + currentDeployment.getName());

            //3. check if the deployment is running
            // Wait for new pod to run and then wait a bit more for load balancer to catch up.
            // Find if correct pod is running.
            System.out.println("Trying to find the new pod, not waiting infinitely...");

            int numNewPodChecks = 15;
            for (int i = 0; i < numNewPodChecks; i++) {
                boolean podDiscovered = false;
                //Refresh node
                currentNode = new Node(currentNode.getName());
                //Check if pod from the latest deployment is running on the current node
                for (IPod pod: currentNode.getPods()) {
                    if (pod.getName().contains(deploymentId)) {
                        podDiscovered = pod.getPhase().equalsIgnoreCase("running");
                    }
                }
                if (podDiscovered) {
                    System.out.println("Pod found! Deleting previous deployments.");
                    // Waiting for load balancer to catch up.
                    Thread.sleep(1000);
                    //4. delete the previous deployments
                    for (IDeployment prevDeployment: previousDeployments) {
                        prevDeployment.delete();
                    }
                    break;
                } else if (i < numNewPodChecks - 1) {
                    System.out.println("Did not find new pod, waiting 1 second.");
                    Thread.sleep(1000);
                }
            }

            System.out.println("============= Target moving done ============\n");


        } catch (NodeNotFoundException | ApplyException | NodeLabelException | PodNotFoundException |
                 InterruptedException e) {
            e.printStackTrace();
        } catch (DeploymentDeleteException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Used for running the MTD algorithm for a number of swaps, then it cancels.
     *
     * @param nSwaps The number of times the algorithm should swap before it cancels.
     * @return Returns a list of logging events
     */
    @Override
    public List<String> run(int nSwaps) {
        //if nSwaps == 1, call the moveTarget() function and return null
        if (nSwaps == 1) {
            moveTarget();
            return null;
        }

        List<String> log = new ArrayList<>(); // Logs the swaps.

        // Delete old deployment if exists.
        System.out.println("Starting MTD alg.");
        try {
            // todo Delete all deployments instead of the one. Makes it easier to restart the MTD.
            IDeployment oldDeployment = new Deployment(deployments.get(0).getName(), "default");
            System.out.println("Deleting old deployment");
            oldDeployment.delete();
        } catch (DeploymentNotFoundException | DeploymentDeleteException ignored) {
        }
        try {
            // Delete old labels if exists.
            List<INode> nodeList = NodeTools.getWorkerNodes();
            for (INode node : nodeList) {
                if (node.getLabels().containsKey(LABEL_KEY)) {
                    System.out.println("Deleting label from: " + node.getName());
                    node.deleteLabel(LABEL_KEY);
                }
            }
        } catch (NodeNotFoundException | NodeLabelException ignored) {
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Not enough worker nodes.");
        }

        // Make it loop infinitely if nSwaps = 0.
        int i = 0;
        // Make it loop infinitely if nSwaps = 0.
        if (nSwaps == 0) {
            i = -1;
        }

        int deploymentCounter = 1;
        String oldDeploymentName = "";
        //TODO: make this into a for-loop since the # of iterations is known
        while (i < nSwaps) {
            try {
                // Get all available worker nodes.
                List<INode> nodeList = NodeTools.getWorkerNodes();
                // If currentNode has been selected before, then remove it from the list to prevent it from getting chosen again.

                if (currentNode != null) {
                    System.out.println("Removing " + currentNode.getName() + " from next random selection.");
                    nodeList.removeIf(tmpNode -> tmpNode.getName().equals(currentNode.getName()));
                }
                // Choose a random node out of the remaining available, then set it to active.
                Random random = new Random();
                int randIntNode = random.nextInt(nodeList.size());
                // Remember old node so we can delete active label later.
                INode oldNode = currentNode;
                // Set new current node.
                currentNode = nodeList.get(randIntNode);

                System.out.println("Randomly selected node: " + currentNode.getName() + ", adding active label.");
                currentNode.addLabel(LABEL_KEY, LABEL_VALUE);
                // Choose a random deployment, can select same again
                int randIntDeployment = random.nextInt(deployments.size());

                // Swap active deployment

                currentDeployment = deployments.get(randIntDeployment);
                System.out.println("Randomly selected Deployment: " + currentDeployment.getFileName());

                log.add(String.format("Node: %s, Deployment: %s", currentNode.getName(), currentDeployment.getFileName()));


                // Start a second pod on the new currentNode
                currentDeployment.apply(deploymentCounter); // Deployment counter is necessary to make the deployments separate while deleting later.
                System.out.println("Applying deployment with name: " + currentDeployment.getName());


                // Wait for new pod to run and then wait a bit more for load balancer to catch up.
                // Find if correct pod is running.
                System.out.println("Trying to find the new pod...");
                while (!(currentNode.getPods().size() == 1 && currentNode.getPods().get(0).getPhase().equalsIgnoreCase("running"))) {
                    System.out.println("Did not find new pod, waiting 1 second.");
                    Thread.sleep(1000);
                    // Refresh node
                    currentNode = new Node(currentNode.getName());
                }
                // Pod is running. Label no longer needed, delete it.
                System.out.println("Deleting active label on node: " + currentNode.getName());
                currentNode.deleteLabel(LABEL_KEY);

                // Waiting for load balancer to catch up.
                Thread.sleep(1000);

                if (!oldDeploymentName.isEmpty()) { // Empty on the first iteration.
                    // Delete the old deployment
                    System.out.println("Deleting deployment named: " + oldDeploymentName);
                    IDeployment oldDeployment = new Deployment(oldDeploymentName, "default");
                    oldDeployment.delete();
                }

                // Save deployment name so that we can separate it from the new one later.
                oldDeploymentName = currentDeployment.getName();
                deploymentCounter++;

                System.out.println("Swap finished, waiting " + timeBetweenSwap + "ms before next iteration.");
                Thread.sleep(timeBetweenSwap);
                System.out.println("============= Iteration Done ============\n");

            } catch (NodeNotFoundException | NodeLabelException | ApplyException | InterruptedException |
                     PodNotFoundException | DeploymentDeleteException | DeploymentNotFoundException e) {
                e.printStackTrace();
            }
            // Make it loop infinitely if nSwaps = 0.
            if (nSwaps != 0) {
                i++;
            }
        }
        return log;
    }

    @Override
    public void setTimeBetweenSwap(int milliseconds) {
        timeBetweenSwap = milliseconds;
    }

    @Override
    public int getTimeBetweenSwap() {
        return timeBetweenSwap;
    }
}
