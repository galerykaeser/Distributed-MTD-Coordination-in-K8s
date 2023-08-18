from datetime import datetime
import subprocess

import pytz


def create_timestamp():
    dt_now = datetime.now(pytz.timezone('Europe/Paris'))
    return f"{dt_now.strftime('%Y-%m-%dT%H:%M:%S')}.{round(dt_now.microsecond / 1000):03}"


victim = 'mtd-zk-0'

print(f'Deleting Pod {victim} at {create_timestamp()}.')

subprocess.run(f'microk8s kubectl delete po -n mtd {victim}', shell=True, stdout=subprocess.PIPE,
               stderr=subprocess.STDOUT)
