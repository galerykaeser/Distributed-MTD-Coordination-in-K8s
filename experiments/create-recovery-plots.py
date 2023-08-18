import datetime
import os.path
import sys

import matplotlib.dates as mdates
import matplotlib.pyplot as plt
import pandas as pd

csv_dir = sys.argv[1]  # get csv dir as argument

if __name__ == '__main__':
    # iterate through each load folder and aggregate csv files, filtering out LOAD lines
    lead_data = {}
    plt.figure().set_figwidth(12)
    node_mapping = {
        'ubuntu22-b': 1,
        'ubuntu22-c': 2,
        'ubuntu22-d': 3,
        'ubuntu22-e': 4,
        'ubuntu22-f': 5
    }

    instants = []
    node_indices = []

    # iterate over all log files
    for csv_file in os.listdir(csv_dir):
        abs_csv_path = os.path.join(csv_dir, csv_file)

        # mtd ensemble logs
        if csv_file != 'client.csv':
            candidate_df = pd.read_csv(abs_csv_path, skipinitialspace=True, header=None)  # read csv file into DataFrame
            candidate_df.columns = ['type', 'time', 'node', 'candidate', 'value']

            # iterate over each CSV row of the current ensemble member
            for _, row in candidate_df.iterrows():
                # fetch the time of the log output
                instant = datetime.datetime.strptime(row['time'], '%Y-%m-%dT%H:%M:%S.%f')
                instants.append(instant)

                # fetch the name of the node this ensemble member is running on
                node_name = row['node']
                node_index = node_mapping[node_name]  # get int mapping of the node, for the plot
                node_indices.append(node_index)

                leading_phase = row['value']  # either "START" ord "END"

                # plot one point on the graph for this specific event, green if "START" and red if "END"
                plt.scatter(instant, node_index, color='green' if leading_phase == 'START' else 'red', marker='|', s=30)

        else:  # parsing data from the client calling the target

            # read in the client's CSV data
            client_df = pd.read_csv(abs_csv_path, skipinitialspace=True, header=None)
            client_df.columns = ['time', 'node', 'latency']
            client_df.dropna(inplace=True)  # drop "na" rows that occur occasionally

            # create lists of timestamps and node indices for plotting the nodes where the target is over time
            client_times = [datetime.datetime.strptime(t, '%Y-%m-%dT%H:%M:%S.%f') for t in client_df['time']]
            client_node_indices = [node_mapping[n] for n in client_df['node']]

    # group (timestamp, node_index) pairs into tuples and sort them by time, required for correct plotting
    ensemble_data = list(zip(instants, node_indices))
    ensemble_data.sort(key=lambda tup: tup[0])


    # plot the node where the ensemble leader is over time
    plt.plot([i[0] for i in ensemble_data], [i[1] for i in ensemble_data], color='mediumseagreen', linewidth=1,
             label='MTD leader', zorder=0)

    # plot dots for each successful call to the target, showing the node it resides on over time
    plt.scatter(client_times, client_node_indices, color='sandybrown', s=1, label='target')

    # plot a bar indicating when the ensemble Pod on node ubuntu22-e was deleted
    plt.bar(datetime.datetime.strptime('2023-07-28T11:41:57.124', '%Y-%m-%dT%H:%M:%S.%f'), 5.2, 0.000005,
            color='magenta', label='pod deletion on ubuntu22-e at 11:41:57')

    # plot a bar indicating when the first leader went through START after recovery
    plt.bar(datetime.datetime.strptime('2023-07-28T11:43:04.927', '%Y-%m-%dT%H:%M:%S.%f'), 5.2, 0.000005,
            color='blue', label='first lead START at 11:43:05 after ensemble recovery')

    # replace the node indices on the y-axis with the node names
    plt.yticks((1, 2, 3, 4, 5), ('ubuntu22-b', 'ubuntu22-c', 'ubuntu22-d', 'ubuntu22-e', 'ubuntu22-f'))

    plt.gca().xaxis.set_major_formatter(mdates.DateFormatter('%H:%M:%S'))
    plt.gca().xaxis.set_major_locator(mdates.SecondLocator(interval=30))

    plt.gca().set_axisbelow(True)
    plt.grid()
    plt.legend()
    plt.xlabel('time')
    plt.ylabel('cluster node')
    plt.tight_layout()
    plt.show()
