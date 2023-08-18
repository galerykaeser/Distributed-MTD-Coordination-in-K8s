package leaderelection;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

/**
 * Class for elections solely based on randomness.
 */
public class RandomElection extends Election {

    /**
     * Constructor for RandomElection. Calls the Election constructor with randomWeight 1.0.
     *
     * @param zooKeeper a reference to the local ZooKeeper server.
     * @param ensembleSize the number of candidates in the ensemble.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public RandomElection(ZooKeeper zooKeeper, int ensembleSize) throws InterruptedException, KeeperException {
        super(zooKeeper, ensembleSize, 1.0);
    }

    /**
     * Method for registering a candidate without a corresponding load value.
     *
     * @throws InterruptedException
     * @throws KeeperException
     */
    public void register() throws InterruptedException, KeeperException {
        zooKeeper.create(getMyIdPath(), getHostName().getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    }

    public void unregister() throws InterruptedException, KeeperException {
        zooKeeper.delete(getMyIdPath(), -1);
    }
}
