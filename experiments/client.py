import ast
import sys
import time
from datetime import datetime

import pytz
import requests

ip = sys.argv[1]
port = sys.argv[2]
experiment_duration_seconds = ast.literal_eval(sys.argv[3])

WAIT_SECONDS = 0.2
url = "http://" + ip + ":" + port + "/"


def create_timestamp():
    dt_now = datetime.now(pytz.timezone('Europe/Paris'))
    return f"{dt_now.strftime('%Y-%m-%dT%H:%M:%S')}.{dt_now.microsecond // 1000:03}"


# stay in this loop until the first successful request, then start the experiment timer
while True:
    try:
        requests.get(url, timeout=5)
        break
    except (requests.exceptions.ConnectionError, requests.exceptions.ReadTimeout):
        time.sleep(WAIT_SECONDS)

# start experiment timer
experiment_start_time = time.time()

while True:
    if time.time() - experiment_start_time >= experiment_duration_seconds:
        # end experiment when time is up
        break
    try:
        latency_start_time = time.time()
        res = requests.get(url, timeout=5)
        latency = time.time() - latency_start_time
        node = (res.text.strip('\n'))
        print(f"{create_timestamp()}, {node}, {latency:.6f}")
    except:
        print(f"{create_timestamp()}, , ")

    time.sleep(WAIT_SECONDS)
