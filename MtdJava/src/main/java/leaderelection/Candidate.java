package leaderelection;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;

import java.io.IOException;

/**
 * Class representing a candidate which connects to the local ZooKeeper server and participates in elections.
 */
public class Candidate {
    private final ZKConnection zkConnection;
    private final ZooKeeper zooKeeper;
    private final int ensembleSize;

    /**
     * Constructor for Candidate objects. Establishes a connection to the local ZooKeeper server.
     *
     * @param ensembleSize the number of candidates in the ensemble.
     * @throws IOException
     * @throws InterruptedException
     */
    public Candidate(int ensembleSize) throws IOException, InterruptedException {
        zkConnection = new ZKConnection();
        String zkFQDN = getZKFQDN();
        zooKeeper = zkConnection.connect(zkFQDN);
        this.ensembleSize = ensembleSize;
    }

    /**
     * Method for participating in an election.
     *
     * @param randomWeight the weight of the random component in the election, must be between 0.0 and 1.0.
     * @return true if elected as leader, and false otherwise.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public boolean runElection(double randomWeight) throws InterruptedException, KeeperException {
        // ***CHOOSE ELECTION IMPLEMENTATION HERE***
        Election election = new HeuristicElection(zooKeeper, ensembleSize, randomWeight); //returns when election znode is bootstrapped
//        Election election = new RandomElection(zooKeeper, ensembleSize); //returns when election znode is bootstrapped
        // ***CHOOSE ELECTION IMPLEMENTATION HERE***
        return election.waitForResult();
    }

    /**
     * Method for obtaining the fully qualified domain name (FQDN) of the ZooKeeper server living in the same pod.
     *
     * @return the FQDN of the local ZooKeeper server.
     */
    private String getZKFQDN() {
        String zkFQDNPrefix = System.getenv("HOSTNAME");
        String zkFQDNSuffix = ".zk-hs.mtd.svc.cluster.local";
        return zkFQDNPrefix + zkFQDNSuffix;
    }

}
