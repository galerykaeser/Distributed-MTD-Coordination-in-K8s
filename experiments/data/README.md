### Accuracy of Client Timestamp
In the `create_timestamp()` function of the `client.py` script, microseconds are converted to milliseconds. We used floor division for that, in order to avoid having to modify the digits representing the seconds in cases where the result would be 1000 milliseconds. This affects all the timestamps in all `client.csv` data files.

The timestamp in `pod-deletion-experiment/delete-log.txt`, which was produced by the `delete-pod.py` script, was created using regular division and rounding.