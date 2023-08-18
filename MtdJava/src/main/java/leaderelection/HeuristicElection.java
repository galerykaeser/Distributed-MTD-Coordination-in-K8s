package leaderelection;

import io.kubernetes.client.Metrics;
import io.kubernetes.client.custom.NodeMetrics;
import io.kubernetes.client.openapi.ApiException;
import model.kubernetes.Node;
import model.kubernetes.exception.NodeNotFoundException;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.util.Objects;
import java.util.Optional;

/**
 * Implementation of the Election class for heuristic-based elections.
 */
public class HeuristicElection extends Election {
    public static final double HALF_CPU_LOAD = 0.5;
    public static final double NULL_CPU_LOAD = -1.0;

    private String currentPath;

    /**
     * Constructor for HeuristicElection without randomWeight parameter. Defaults to randomWeight 0.0.
     *
     * @param zooKeeper a reference to the local ZooKeeper server.
     * @param ensembleSize the number of candidates in the ensemble.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public HeuristicElection(ZooKeeper zooKeeper, int ensembleSize) throws InterruptedException, KeeperException {
        super(zooKeeper, ensembleSize, 0.0);
    }

    /**
     * Constructor for HeuristicElection with randomWeight parameter.
     *
     * @param zooKeeper a reference to the local ZooKeeper server.
     * @param ensembleSize the number of candidates in the ensemble.
     * @param randomWeight the weight of the random component in the election.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public HeuristicElection(ZooKeeper zooKeeper, int ensembleSize, double randomWeight) throws InterruptedException, KeeperException {
        super(zooKeeper, ensembleSize, randomWeight);
    }

    /**
     * Method for fetching the current load, storing it in the object variable and creating the registration znode for
     * the current candidate.
     *
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Override
    public void register() throws InterruptedException, KeeperException {
        double relativeCpuLoad = getRelativeCpuLoad();
        expPrintLoad(relativeCpuLoad);
        String myFullPath = getMyIdPath() + UUID_LOAD_DELIMITER + relativeCpuLoad;
        currentPath = myFullPath;
        System.out.printf("Registering with path: %s%n", currentPath);
        zooKeeper.create(myFullPath, getHostName().getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }

    /**
     * Method for printing an output line with the given load.
     *
     * @param load the load value to be printed.
     */
    private void expPrintLoad(double load) {
        System.out.printf("EXP-LOAD, %s, %s, %s, %f%n",
                getCetTimeStamp(),
                getMyNodeName(),
                getHostName(),
                load);
    }

    @Override
    public void unregister() throws InterruptedException, KeeperException {
        tryDeleteZnode(currentPath);
    }

    /**
     * Method for obtaining the relative CPU load of the current cluster node.
     *
     * @return the relative CPU load of the node, or -1.0 if something went wrong.
     */
    private double getRelativeCpuLoad() {
        double absoluteCpuUsage;
        double totalCpuAvailable;
        try {
            Optional<NodeMetrics> optionalNodeMetrics = new Metrics()
                    .getNodeMetrics()
                    .getItems()
                    .stream()
                    .filter(metric -> Objects.equals(metric.getMetadata().getName(), getMyNodeName()))
                    .findFirst();
            if (optionalNodeMetrics.isPresent()) {
                absoluteCpuUsage = optionalNodeMetrics.get()
                        .getUsage()
                        .get("cpu")
                        .getNumber()
                        .doubleValue();
                totalCpuAvailable = new Node(getMyNodeName()).getAllocatableCpu();
            } else {
                System.out.printf("Node metrics data not present, return null load value: %s%n", NULL_CPU_LOAD);
                return NULL_CPU_LOAD;
            }
        } catch (ApiException | NodeNotFoundException e) {
            System.out.printf("Catching %s caused by fetching node metrics:%n", e.getClass());
            System.out.println(e.getMessage());
            System.out.printf("Return null load value: %s%n", NULL_CPU_LOAD);
            return NULL_CPU_LOAD;
        }
        return absoluteCpuUsage / totalCpuAvailable;
    }
}
