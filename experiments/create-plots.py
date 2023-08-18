import ast
import datetime
import os.path
import sys

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

base_dir = sys.argv[1]  # get experiment base dir as argument


def get_sync_delays(lead_dataframe: pd.DataFrame) -> list:
    """
    Compute a list of synchronization delays from a DataFrame.
    :param lead_dataframe: a pandas DataFrame without the LOAD rows
    :return: a list of the synchronization delays computed from the DataFrame
    """
    # filter out all rows that are not START rows
    lead_dataframe = lead_dataframe.query(expr='value == "START"', inplace=False)

    # parse text representations of datetime into a list of datetime objects
    datetimes = [datetime.datetime.strptime(dt, '%Y-%m-%dT%H:%M:%S.%f') for dt in lead_dataframe['time']]

    datetimes.sort()  # sort the list of datetime objects for each START

    # compute sync delays by computing the difference between adjacent START timestamps
    sync_delays = []
    for upper_i in range(1, len(datetimes)):
        sync_delays.append((datetimes[upper_i] - datetimes[upper_i - 1]).total_seconds())

    return sync_delays


if __name__ == '__main__':
    # dict for the sync delays for the entire ensemble
    sync_delays_ensemble = {}  # sync_delays_ensemble[<NUM_LOADED>][<RANDOM_WEIGHT>]

    # dict for the load data per experiment per node
    load_data_nodes = {}  # load_data_nodes[<NUM_LOADED>][<RANDOM_WEIGHT>][<NODE_NAME>]

    # dict for the number of times each node's candidate was elected
    elected_counts_nodes = {}  # elected_counts_nodes[<NUM_LOADED>][<RANDOM_WEIGHT>][<NODE_NAME>]

    negative_loads = 0  # counter for load values "-1.0", just for info

    # iterate over each loaded directory
    for num_loaded in range(4):
        weights_dir = os.path.join(base_dir, f'{num_loaded}-loaded')

        sync_delays_per_weight = {}
        load_data_per_weight = {}
        elected_count_per_weight = {}

        # iterate over each random weight directory
        for csv_dir in os.listdir(weights_dir):
            if csv_dir == 'log.txt':
                continue
            abs_csv_dir = os.path.join(weights_dir, csv_dir)
            weight = ast.literal_eval(csv_dir.split('-')[-1])  # extract the random weight from the directory name

            # init DataFrame for whole ensemble with column names
            ensemble_dataframe = pd.DataFrame(columns=['type', 'time', 'node', 'candidate', 'value'])
            load_per_node = {}
            elected_count_per_node = {}

            # iterate over each mtd-zk-X file
            for csv_file in os.listdir(abs_csv_dir):
                if csv_file == 'client.csv':
                    continue
                abs_csv_file = os.path.join(abs_csv_dir, csv_file)
                candidate_df = pd.read_csv(abs_csv_file, skipinitialspace=True,
                                           header=None)  # read csv file into DataFrame
                candidate_df.columns = ['type', 'time', 'node', 'candidate', 'value']

                # check that the data from one candidate only lists one node and fetch the name of that node
                unique_nodes = set(candidate_df['node'])
                assert len(unique_nodes) == 1
                node_name = unique_nodes.pop()

                load_values = []

                # only keep rows with LOAD values and fetch column containing the LOAD values
                load_str_values = candidate_df.query(expr='type == "EXP-LOAD"')['value']

                # parse the float values from their string representations; make into strings first.
                # some load values were already floats in the DataFrame, but this breaks the parser which expects a str
                for val in load_str_values:
                    parsed_val = ast.literal_eval(str(val))
                    assert type(parsed_val) == float

                    # filter out load values of "-1", but count their occurrences
                    if parsed_val < 0:
                        negative_loads += 1
                        continue

                    load_values.append(parsed_val)

                # store the parsed load values behind the respective node name as the key
                assert node_name not in load_per_node
                load_per_node[node_name] = load_values

                # count number of times elected for that candidate
                assert node_name not in elected_count_per_node
                elected_count_per_node[node_name] = len(candidate_df.query(expr='value == "START"'))

                # concatenate the current candidate DataFrame with the ensemble DataFrame
                ensemble_dataframe = pd.concat([ensemble_dataframe, candidate_df], ignore_index=True)

            # filter out LOAD lines and compute a list of sync delays for the entire ensemble
            sync_delays_per_weight[weight] = get_sync_delays(ensemble_dataframe.query(expr='type == "EXP-LEAD"'))

            load_data_per_weight[weight] = load_per_node
            elected_count_per_weight[weight] = elected_count_per_node

        sync_delays_ensemble[num_loaded] = sync_delays_per_weight
        load_data_nodes[num_loaded] = load_data_per_weight
        elected_counts_nodes[num_loaded] = elected_count_per_weight

    print('num negative loads', negative_loads)

    # create plots for RQ2
    plt.figure(1)
    num_cols = 6
    fig, ax = plt.subplots(2, num_cols)
    fig.set_figwidth(12)
    fig.set_figheight(8)
    loaded_vals = (0, 1, 2, 3)
    random_weights = (0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    for i, rw in enumerate(random_weights):
        ax[i // num_cols, i % num_cols].boxplot(
            [load_config[rw] for load_config in list(sync_delays_ensemble.values())],
            positions=list(sync_delays_ensemble.keys()),
            showmeans=True,
            showfliers=False,
            # showfliers=True,  # flier settings
            meanprops={'marker': '.'}, medianprops={'color': 'red'},
            flierprops={'marker': '.', 'markersize': 3})
        ax[i // num_cols, i % num_cols].grid()
        ax[i // num_cols, i % num_cols].set(yticks=np.arange(3, 15, 0.5))
        ax[i // num_cols, i % num_cols].set_ylim([3, 14.5])
        # ax[i // num_cols, i % num_cols].set(yticks=np.arange(3, 26, 1))  # flier settings
        # ax[i // num_cols, i % num_cols].set_ylim([2.5, 25.5])  # flier settings
        ax[i // num_cols, i % num_cols].set_xlabel('# loaded nodes')
        ax[i // num_cols, i % num_cols].set_ylabel('synchronization delay [s]')
        ax[i // num_cols, i % num_cols].set_title(f'random weight {rw}')

    plt.tight_layout()
    plt.show()

    # create load plots
    # load_data_nodes[<NUM_LOADED>][<RANDOM_WEIGHT>][<NODE_NAME>]
    plt.figure(2)
    fig, ax = plt.subplots(1, len(loaded_vals))
    fig.set_figwidth(12)
    fig.set_figheight(5)
    random_weights = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]
    node_names = ('ubuntu22-b', 'ubuntu22-c', 'ubuntu22-d')

    for num_loaded in loaded_vals:
        loads_per_node = []
        for node_name in node_names:
            node_loads = []
            for random_weight in random_weights:
                node_loads += load_data_nodes[num_loaded][random_weight][node_name]
            loads_per_node.append(node_loads)
        ax[num_loaded].boxplot(loads_per_node,
                               showmeans=True, showfliers=False,
                               meanprops={'marker': '.', 'markersize': 4},
                               flierprops={'marker': '.', 'markersize': 1},
                               medianprops={'color': 'red'})
        ax[num_loaded].grid()
        ax[num_loaded].set_xticklabels(node_names, rotation=10)
        ax[num_loaded].set_ylim([0.0, 0.8])
        ax[num_loaded].set_title(f'{num_loaded} loaded')
        ax[num_loaded].set_xlabel('cluster node')
        ax[num_loaded].set_ylabel('relative load')

    plt.tight_layout()
    plt.show()

    # create plots for RQ3
    plt.figure(3)
    num_cols = 4
    fig, ax = plt.subplots(1, num_cols)
    fig.set_figwidth(12)
    fig.set_figheight(5)

    for num_loaded in loaded_vals:
        ax[num_loaded % num_cols].boxplot([sync_delays_ensemble[num_loaded][rw] for rw in random_weights],
                                          showmeans=True,
                                          showfliers=False,
                                          # showfliers=True,  # flier settings
                                          meanprops={'marker': '.', 'markersize': 4},
                                          flierprops={'marker': '.', 'markersize': 1},
                                          medianprops={'color': 'red'})
        ax[num_loaded % num_cols].grid()
        ax[num_loaded % num_cols].set_xticklabels(random_weights, rotation=45)
        ax[num_loaded % num_cols].set(yticks=np.arange(3, 15, 0.5))
        ax[num_loaded % num_cols].set_ylim([3, 14.5])
        # ax[num_loaded % num_cols].set(yticks=np.arange(3, 26, 1))  # flier settings
        # ax[num_loaded % num_cols].set_ylim([2.5, 25.5])  # flier settings
        ax[num_loaded % num_cols].set_title(f'{num_loaded} loaded')
        ax[num_loaded % num_cols].set_xlabel('random weight')
        ax[num_loaded % num_cols].set_ylabel('synchronization delay [s]')

    plt.tight_layout()
    plt.show()

    # create num elected plots
    # elected_counts_nodes[<NUM_LOADED>][<RANDOM_WEIGHT>][<NODE_NAME>]
    plt.figure(4)
    num_cols = 2
    fig, ax = plt.subplots(2, 2)
    fig.set_figwidth(12)
    fig.set_figheight(7)
    random_weights = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]
    colors = ('green', 'orange', 'blue')
    bar_width = 0.02
    offsets = [bar_width * i for i in range(-1, 2)]

    for num_loaded in loaded_vals:
        for node_name, color, offset in zip(node_names, colors, offsets):
            elected_counts = [elected_counts_nodes[num_loaded][rw][node_name] for rw in random_weights]
            ax[int(num_loaded / num_cols), num_loaded % num_cols].bar([rw + offset for rw in random_weights],
                                                                      elected_counts, bar_width)
        ax[int(num_loaded / num_cols), num_loaded % num_cols].grid()
        ax[int(num_loaded / num_cols), num_loaded % num_cols].set_xticks(random_weights)
        # ax[int(num_loaded / num_cols), num_loaded % num_cols].set_ylim([0, 410])
        ax[int(num_loaded / num_cols), num_loaded % num_cols].set_xlabel('random weight')
        ax[int(num_loaded / num_cols), num_loaded % num_cols].set_ylabel('election count')
        ax[int(num_loaded / num_cols), num_loaded % num_cols].legend(node_names, loc=f'{"lower" if num_loaded != 2 else "upper"} right')
        ax[int(num_loaded / num_cols), num_loaded % num_cols].set_title(f'{num_loaded} loaded')

    plt.tight_layout()
    plt.show()
