package leaderelection;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Parent class of the two types of election, defining most of the election functionality.
 */
public abstract class Election {
    protected static final String UUID_LOAD_DELIMITER = "_";
    protected static final String NAME_UUID_DELIMITER = "#";
    protected static final String ELECTION_PATH = "/election";
    protected static final String BARRIER_1_PATH = "/barrier1";
    protected static final String BARRIER_2_PATH = "/barrier2";
    private static final long TIMEOUT_MILLISECONDS = 30_000;
    protected static final String TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";
    protected static ZoneId CET_ZONE_ID = ZoneId.of("Europe/Paris");

    protected final ZooKeeper zooKeeper;
    protected final UUID identity;
    protected final int ensembleSize;
    private final double randomWeight;

    protected boolean elected = false;

    /**
     * Constructor for instances of Election. Instantiates the candidate's UUID for the election, and attempts to
     * bootstrap the znodes required for the election.
     *
     * @param zooKeeper a reference to the local ZooKeeper server.
     * @param ensembleSize the number of candidates in the ensemble.
     * @param randomWeight the weight of the random component in the election.
     * @throws InterruptedException
     * @throws KeeperException
     */
    public Election(ZooKeeper zooKeeper, int ensembleSize, double randomWeight) throws InterruptedException, KeeperException {
        //initialize the final variables
        this.zooKeeper = zooKeeper;
        identity = UUID.randomUUID();
        this.ensembleSize = ensembleSize;
        this.randomWeight = randomWeight;

        //bootstrap znode structure required for elections and synchronization
        for (String znodePath : new String[]{ELECTION_PATH, BARRIER_1_PATH, BARRIER_2_PATH}) {
            bootstrapZnode(znodePath);
        }
    }

    /**
     * Method for creating a znode if it does not exist yet.
     *
     * @param znodePath the path to be used for the znode.
     */
    private void bootstrapZnode(String znodePath) throws InterruptedException, KeeperException {
        if (!znodeExists(znodePath)) {
            tryCreateZnode(znodePath, true);
        }
    }

    /**
     * Method for checking whether a znode with a given path exists.
     *
     * @param znodePath the path of the znode to be checked.
     * @return true if the znode exists, and false otherwise.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private boolean znodeExists(String znodePath) throws InterruptedException, KeeperException {
        return zooKeeper.exists(znodePath, false) != null;
    }

    /**
     * Method for obtaining the hostname of the system.
     *
     * @return the value of the HOSTNAME environment variable.
     */
    public static String getHostName() {
        return System.getenv("HOSTNAME");
    }

    /**
     * Method for obtaining the name of the current Kubernetes node.
     *
     * @return the value of the MY_NODE_NAME environment variable.
     */
    public static String getMyNodeName() {
        return Objects.requireNonNull(System.getenv("MY_NODE_NAME"));
    }

    /**
     * Method for registering the current candidate for the election.
     */
    public abstract void register() throws InterruptedException, KeeperException;

    /**
     * Method for attempting to create a znode.
     *
     * @param znodePath the path for the znode to be created.
     * @param persistent true if the znode should be persistent, and false if it should be ephemeral.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private void tryCreateZnode(String znodePath, boolean persistent) throws InterruptedException, KeeperException {
        try{
            zooKeeper.create(znodePath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, persistent ? CreateMode.PERSISTENT : CreateMode.EPHEMERAL);
        } catch (KeeperException.NodeExistsException e) {
            System.out.printf("Znode %s already exists!%n", znodePath);
        }
    }

    /**
     * Method for attempting to delete a znode.
     *
     * @param znodePath the path of the znode to be deleted.
     * @throws InterruptedException
     * @throws KeeperException
     */
    protected void tryDeleteZnode(String znodePath) throws InterruptedException, KeeperException {
        try {
            zooKeeper.delete(znodePath, -1);
            System.out.printf("Successfully deleted the %s znode%n", znodePath);
        } catch (KeeperException.NoNodeException e) {
            System.out.printf("%s does not exist!%n", znodePath);
        }
    }

    /**
     * Method for obtaining the current candidate's znode path, up to and including its UUID.
     *
     * @return the current candidate's znode path, up to and including its UUID.
     */
    protected String getMyIdPath() {
        return ELECTION_PATH + "/" + getHostName() + NAME_UUID_DELIMITER + identity;
    }

    /**
     * Method for unregistering the current candidate from an election.
     *
     * @throws InterruptedException
     * @throws KeeperException
     */
    public abstract void unregister() throws InterruptedException, KeeperException;

    /**
     * Method for obtaining a list of participants from the list of znode IDs.
     * If load numbers are present, they are separated from the UUIDs by a special delimiter.
     * If no load value is found, a default value of 0.5 is used.
     *
     * @param registeredCandidates the list of znode IDs to extract the participants from.
     * @return the extracted list of participants.
     */
    private List<Participant> extractParticipants(List<String> registeredCandidates) {
        List<Participant> extractedParticipants = new ArrayList<>();

        for (String znodeId : registeredCandidates) {
            String id;
            double load;
            if (znodeId.contains(UUID_LOAD_DELIMITER)) { //there is a load number embedded in the znode ID
                String[] idAndLoad = znodeId.split(UUID_LOAD_DELIMITER);
                id = idAndLoad[0];
                String loadStr = idAndLoad[1];
                load = Double.parseDouble(loadStr);
            } else { //no load value discovered, create participant with half CPU load
                id = znodeId;
                load = HeuristicElection.HALF_CPU_LOAD;
            }
            String[] nameAndUuid = id.split(NAME_UUID_DELIMITER);
            String name = nameAndUuid[0];
            String uuidStr = nameAndUuid[1];
            extractedParticipants.add(new Participant(name, uuidStr, load, randomWeight));
        }

        return extractedParticipants;
    }

    /**
     * Method for performing all steps of an election. Waits for all candidates to be present, evaluates whether the
     * current candidate won and returns the election result of the current candidate.
     *
     * @return true if the current candidate won, and false otherwise.
     */
    public boolean waitForResult() throws InterruptedException, KeeperException {
        //1. sync at barrier 1, no timeout
        enterBarrier(BARRIER_1_PATH, false);

        //2. delete previous barrier 2 znode
        leaveBarrier(BARRIER_2_PATH);

        //3. wait for all candidates to register
        register();

        //4. wait until enough candidates are registered
        List<String> registeredCandidates = waitForRegistrations();
        if (registeredCandidates == null) {
            unregister();
            return false;
        }

        //5. determine the election result
        System.out.println("Determining the election result...");
        List<Participant> extractedParticipants = extractParticipants(registeredCandidates);
        System.out.printf("Extracted election participants:%n%s%n", extractedParticipants);
        boolean result = wonTheElection(extractedParticipants);

        //6. free barrier 1, enter barrier 2
        leaveBarrier(BARRIER_1_PATH);
        boolean successful = enterBarrier(BARRIER_2_PATH, true);

        //7. unregister, return false or election result
        unregister();
        if (!successful) {
            return false;
        } else {
            return result;
        }
    }

    /**
     * Method for waiting for the registration of all candidates. Checks repeatedly whether the correct number of
     * candidates is registered. Returns null if the timeout expires while waiting.
     *
     * @return a list of the names of all candidates' znodes, or null if the timeout expires before all have registered.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private List<String> waitForRegistrations() throws InterruptedException, KeeperException {
        List<String> registeredCandidates = zooKeeper.getChildren(ELECTION_PATH, false);
        long startTime = System.currentTimeMillis();

        while (registeredCandidates.size() < ensembleSize) {
            if (System.currentTimeMillis() - startTime >= TIMEOUT_MILLISECONDS) {
                return null; //waited for too long, timeout and return null
            }
            registeredCandidates = zooKeeper.getChildren(ELECTION_PATH, false);
        }
        return registeredCandidates;
    }

    /**
     * Method for leaving a synchronization barrier. Deletes the barrier child znode of the current candidate and waits
     * for the ones of the other candidates to disappear.
     *
     * @param barrierPath the path of the znode of the barrier to be left.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private void leaveBarrier(String barrierPath) throws InterruptedException, KeeperException {
        String myBarrierPath = String.format("%s/%s", barrierPath, getHostName());
        tryDeleteZnode(myBarrierPath);

        while (!zooKeeper.getChildren(barrierPath, false).isEmpty()) {
            Thread.sleep(1000);
        }
        System.out.printf("Leaving %s!%n", barrierPath);
    }

    /**
     * Method for entering a synchronization barrier. Creates a barrier child znode for the current candidate and waits
     * for all other candidates to do the same. Optionally, a timeout can be used. The timeout will, if triggered, lead
     * the method to return false.
     *
     * @param barrierPath the path of the znode of the barrier to be entered.
     * @param useTimeout whether a timeout should be used, true means timeout, false means no timeout.
     * @return true when all candidates entered the barrier, false if the timeout expired.
     * @throws InterruptedException
     * @throws KeeperException
     */
    private boolean enterBarrier(String barrierPath, boolean useTimeout) throws InterruptedException, KeeperException {
        //wait for all to enter, return true when all entered and false if timeout triggered before
        System.out.printf("Entering %s!%n", barrierPath);
        String myBarrierPath = String.format("%s/%s", barrierPath, getHostName());
        tryCreateZnode(myBarrierPath, false);
        long startTime = System.currentTimeMillis();

        while (zooKeeper.getChildren(barrierPath, false).size() < ensembleSize) {
            if (useTimeout && System.currentTimeMillis() - startTime >= TIMEOUT_MILLISECONDS) {
                return false; //waited for too long, timeout and return false
            }
            Thread.sleep(1000);
        }
        return true;
    }

    /**
     * Method for determining whether the current candidate won based on the sorted list of participants.
     *
     * @param participants the list of candidates who participated in this election.
     * @return true if the current candidate won the election, false otherwise.
     */
    protected boolean wonTheElection(List<Participant> participants) {
        participants.sort(null);
        elected = participants.get(0).getIdentifier().equals(identity);
        System.out.println(elected ? "Won election!" : "Lost election...");
        return elected;
    }

    /**
     * Method for printing an output line indicating the beginning of a candidate's leading phase.
     */
    public static void expPrintLeadStart() {
        System.out.printf("EXP-LEAD, %s, %s, %s, START%n",
                getCetTimeStamp(),
                getMyNodeName(),
                getHostName());
    }

    /**
     * Method for printing an output line indicating the end of a candidate's leading phase.
     */
    public static void expPrintLeadEnd() {
        System.out.printf("EXP-LEAD, %s, %s, %s, END%n",
                getCetTimeStamp(),
                getMyNodeName(),
                getHostName());
    }

    /**
     * Method for creating a timestamp matching the Europe/Paris timezone.
     * TODO: changing this to UTC might make more sense to avoid jumps in timestamps due to daylight savings time
     *
     * @return the created timestamp as a string.
     */
    public static String getCetTimeStamp() {
        return LocalDateTime.now(Clock.system(CET_ZONE_ID)).format(DateTimeFormatter.ofPattern(TIME_FORMAT));
    }
}
