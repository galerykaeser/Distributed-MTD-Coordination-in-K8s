package leaderelection;

import java.util.UUID;

/**
 * Class representing a candidate that registered for an election.
 */
public class Participant implements Comparable<Participant> {
    private final String name;
    private final UUID identifier;
    private final double load;
    double randomWeight;

    /**
     * Constructor for a Participant. Makes sure that the load value cannot be greater than 1.0.
     * @param name the name of the participant.
     * @param uuidStr the string version of the participant's UUID.
     * @param load the load value associated with the participant.
     * @param randomWeight the randomWeight used in this election.
     */
    public Participant(String name, String uuidStr, double load, double randomWeight) {
        this.name = name;
        identifier = UUID.fromString(uuidStr);
        if (load > 1.0) {
            load = 1.0;
        }
        //we made sure that the load is at most 1.0
        this.load = load;
        this.randomWeight = randomWeight;
    }

    @Override
    public String toString() {
        return String.format("%s_%s", name, String.format("%.3f", load));
    }

    /**
     * Compares this participant instance to another
     * Returns -1 if this instance is smaller than the other, and 1 otherwise.
     * Never returns 0, participants' UUIDs are used as tie-breakers.
     *
     * @param participant the participant instance to compare this instance to
     * @return the value determining the order, either -1 or 1
     */
    @Override
    public int compareTo(Participant participant) {
        double myOrderNumber = getOrderNumber();
        double otherOrderNumber = participant.getOrderNumber();
        if (myOrderNumber < otherOrderNumber) {
            return -1;
        } else if (myOrderNumber > otherOrderNumber) {
            return 1;
        } else { //order numbers seem to be identical
            return identifier.compareTo(participant.getIdentifier());
        }
    }

    /**
     * Returns the weighted sum of the UUID and the load, as determined by the randomWeight field.
     * The returned value is in the range [0, 1].
     *
     * @return weighted sum of the UUID and the load
     */
    public double getOrderNumber() {
        double randomPart = (double) identifier.getMostSignificantBits() / Long.MAX_VALUE;
        return randomWeight * randomPart + (1.0 - randomWeight) * load;
    }

    public UUID getIdentifier() {
        return identifier;
    }
}
