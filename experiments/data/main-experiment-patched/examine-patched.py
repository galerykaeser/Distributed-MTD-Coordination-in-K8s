import re
import sys
import datetime

if __name__ == '__main__':
    filepath = sys.argv[1]

    with open(filepath, 'r') as file:
        file_content = file.read()
        pattern = re.compile('===========================================================\n(\d-loaded/3600-3-\d\.\d)\nindividual candidate LOAD count\n((?:\d+\n){3})total ensemble START count\n(\d+\n)first lines\n((?:2023-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.\d{3}\n){4})last lines\n((?:2023-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.\d{3}\n){4})===========================================================\n')
        capture = re.findall(pattern, file_content)
        shortest = '', datetime.timedelta.max
        longest = '', datetime.timedelta.min
        for run_data in capture:
            run_id = run_data[0]
            unique_counts = set([int(num_str) for num_str in (run_data[1] + run_data[2]).strip().split('\n')])
            if len(unique_counts) > 2:
                print(f'WARNING, examine {run_id}')
            if len(unique_counts) == 2:
                difference = abs(unique_counts.pop() - unique_counts.pop())
                if difference > 1:
                    print(f'WARNING, examine {run_id}')

            run_starts = run_data[3].strip().split('\n')
            run_ends = run_data[4].strip().split('\n')
            for start_str in run_starts:
                for end_str in run_ends:
                    start_dt = datetime.datetime.strptime(start_str, '%Y-%m-%dT%H:%M:%S.%f')
                    end_dt = datetime.datetime.strptime(end_str, '%Y-%m-%dT%H:%M:%S.%f')
                    delta = abs(start_dt - end_dt)
                    if delta < shortest[1]:
                        shortest = run_id, delta
                    if delta > longest[1]:
                        longest = run_id, delta
        print('shortest:', shortest)
        print('longest:', longest)
