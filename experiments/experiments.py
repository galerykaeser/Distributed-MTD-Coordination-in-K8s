import ast
import multiprocessing
import os.path
import re
import subprocess
import sys
import time
from multiprocessing.pool import ThreadPool

from kubernetes import client, config

MTD_NAMESPACE = 'mtd'

IMAGE_COMMAND_KIND = 'kind load docker-image mtd-zk:kind -n mtd-3'
IMAGE_COMMAND_MICROK8S = 'docker push localhost:32000/mtd-zk:registry'
MAVEN_COMPILE_COMMAND_STUB_KIND = 'mvn compile com.google.cloud.tools:jib-maven-plugin:3.3.1:dockerBuild ' \
                                  '-Djib.to.image=docker.io/mtd-zk:kind ' \
                                  f'-Djib.container.args=DistMtdTestSettings.yaml,'  # ensemble size and random weight are added later
MAVEN_COMPILE_COMMAND_STUB_MICROK8S = 'mvn compile com.google.cloud.tools:jib-maven-plugin:3.3.1:dockerBuild ' \
                                      '-Djib.to.image=localhost:32000/mtd-zk:registry ' \
                                      f'-Djib.container.args=DistMtdTestSettings.yaml,'  # ensemble size and random weight are added later
SERVICE_IP_KIND = '172.18.255.200'
SERVICE_IP_MICROK8S = '192.168.122.20'

config.load_kube_config()
v1 = client.CoreV1Api()
client_script_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'client.py')


def print_usage():
    print('Script should be used like this:')
    print(
        'experiments.py <EXPERIMENT_DIR> <POM_DIR> <YAML_FILE> <ENSEMBLE_SIZE> <RANDOM_WEIGHTS_LIST> <IS_MICROK8S> <DURATION_SECONDS>')


def run_init_commands(is_microk8s):
    init_commands = [
        maven_compile_command,
        image_command,
        f'{"microk8s " if is_microk8s else ""}kubectl apply -f {yaml_file}'
    ]
    for command in init_commands:
        print(f'Executing init command: {command}')
        subprocess.run(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def run_teardown_commands(is_microk8s):
    teardown_commands = [
        f'{"microk8s " if is_microk8s else ""}kubectl delete -f {yaml_file} --wait=true',
        f'{"microk8s " if is_microk8s else ""}kubectl delete deploy --all -n default --wait=true',
        f'{"microk8s " if is_microk8s else ""}kubectl delete svc dist-mtd-lb-service -n default --wait=true',

        f'{"microk8s " if is_microk8s else ""}kubectl wait --for=delete -f {yaml_file} --timeout=-1s',
        f'{"microk8s " if is_microk8s else ""}kubectl wait --for=delete pod -n mtd --all --timeout=-1s',
        f'{"microk8s " if is_microk8s else ""}kubectl wait --for=delete pod -n default --all --timeout=-1s',
        f'{"microk8s " if is_microk8s else ""}kubectl wait --for=delete svc/dist-mtd-lb-service -n default --timeout=-1s'
    ]
    for command in teardown_commands:
        print(f'Executing teardown command: {command}')
        subprocess.run(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def capture_logs(logs_parent_dir, is_microk8s):
    # wait for first running pod
    while True:
        found_running = False
        pods = v1.list_namespaced_pod(MTD_NAMESPACE)
        for pod in pods.items:
            if pod.status.phase.casefold() == 'running'.casefold():
                found_running = True
                break
        if found_running:
            break
        time.sleep(3)

    # spawn processes to write pod logs as long as there are running pods
    unique_pods = set()
    capture_log_procs = []
    sanity_check_procs = []

    while True:
        found_running = False
        pods = v1.list_namespaced_pod(MTD_NAMESPACE)
        for pod in pods.items:
            if pod.status.phase.casefold() == 'running'.casefold():
                found_running = True
                pod_name, pod_uid = pod.metadata.name, pod.metadata.uid
                unique_identifier = f'{pod_name}-{pod_uid}'
                if unique_identifier not in unique_pods:
                    print(f'Capturing logs of {pod_name}')
                    # start capturing logs for this pod
                    unique_pods.add(unique_identifier)
                    capture_log_procs.append(
                        subprocess.Popen(
                            f'{"microk8s " if is_microk8s else ""}kubectl logs -n mtd {pod_name} -c mtd-coordinator -f | grep EXP > {logs_parent_dir}/{unique_identifier}.csv',
                            shell=True,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                            text=True
                        )
                    )
                    # perform sanity check as well
                    sanity_check_procs.append(
                        subprocess.Popen(
                            f'{"microk8s " if is_microk8s else ""}kubectl logs -n mtd {pod_name} -c mtd-coordinator -f | grep INIT',
                            shell=True,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                            text=True
                        )
                    )
        if not found_running:
            break
        time.sleep(3)

    pod_and_proc_counts = {len(unique_pods), len(capture_log_procs), len(sanity_check_procs)}

    for proc in capture_log_procs:
        output = proc.communicate()[0]  # wait for process to finish
        if not proc.returncode == 0:
            print(f'WARNING: one process for capturing logs returned with code {proc.returncode}.')
            print(f'Output: {output}')

    captured_ensemble_sizes = set()
    captured_random_weights = set()

    for proc in sanity_check_procs:
        output = proc.communicate()[0]
        assert proc.returncode == 0, output
        # extract ensemble size and random weight from output
        captured_ensemble_size_str, captured_random_weight_str = re.search(
            r'INIT: ensemble size=(\d+), random weight=([0|1].\d+)', output).groups()
        captured_ensemble_size = ast.literal_eval(captured_ensemble_size_str)
        captured_random_weight = ast.literal_eval(captured_random_weight_str)
        captured_ensemble_sizes.add(captured_ensemble_size)
        captured_random_weights.add(captured_random_weight)

    return pod_and_proc_counts, captured_ensemble_sizes, captured_random_weights


if __name__ == '__main__':
    # extract command line parameters
    try:
        experiment_dir = sys.argv[1]
        pom_dir = sys.argv[2]
        yaml_file = sys.argv[3]
        ensemble_size = ast.literal_eval(sys.argv[4])
        random_weights = ast.literal_eval(sys.argv[5])
        is_microk8s = ast.literal_eval(sys.argv[6])
        experiment_duration_seconds = ast.literal_eval(sys.argv[7])
    except IndexError:
        print_usage()
        exit(0)

    assert os.path.exists(experiment_dir), f'Experiment directory {experiment_dir} needs to exist.'

    image_command = IMAGE_COMMAND_MICROK8S if is_microk8s else IMAGE_COMMAND_KIND
    maven_compile_command_stub = MAVEN_COMPILE_COMMAND_STUB_MICROK8S if is_microk8s else MAVEN_COMPILE_COMMAND_STUB_KIND

    service_ip_address = SERVICE_IP_MICROK8S if is_microk8s else SERVICE_IP_KIND

    for random_weight in random_weights:
        print(f'\nRunning experiment for random weight = {random_weight}.')
        maven_compile_command = f'{maven_compile_command_stub}{ensemble_size},{random_weight}'
        os.chdir(pom_dir)

        experiment_sub_dir = os.path.join(experiment_dir,
                                          f'{experiment_duration_seconds}-{ensemble_size}-{random_weight}')
        os.mkdir(experiment_sub_dir)

        # initialize experiment: clean up previous runs, build and place container images, apply YAML file
        run_init_commands(is_microk8s)

        # start process that takes care of redirecting pod logs
        capture_logs_proc = multiprocessing.pool.ThreadPool(processes=1)
        async_result = capture_logs_proc.apply_async(func=capture_logs, args=[experiment_sub_dir, is_microk8s])
        capture_logs_proc.close()

        #  capturing client output; when completed the experiment can end
        subprocess.run(
            f'python {client_script_path} {service_ip_address} 5678 {experiment_duration_seconds} > {experiment_sub_dir}/client.csv',
            shell=True)

        # terminate experiments
        print(f'Terminating experiment with random weight {random_weight}.')
        run_teardown_commands(is_microk8s)

        # print info
        capture_logs_proc.join()
        pod_and_proc_counts, captured_ensemble_sizes, captured_random_weights = async_result.get()

        assert len(pod_and_proc_counts) == 1, f'Mismatch in count of unique pods and capturing processes: {pod_and_proc_counts}'
        pod_and_proc_count = pod_and_proc_counts.pop()

        assert len(captured_ensemble_sizes) == 1, f'Mismatch in ensemble size: {captured_ensemble_sizes}'
        captured_ensemble_size = captured_ensemble_sizes.pop()
        assert ensemble_size == captured_ensemble_size, f'Expected ensemble size {ensemble_size} but got {captured_ensemble_size}'

        assert len(captured_random_weights) == 1, f'Mismatch in random weight: {captured_random_weights}'
        captured_random_weight = captured_random_weights.pop()
        assert random_weight == captured_random_weight, f'Expected random weight {random_weight} but got {captured_random_weight}'

        print(f'Sanity check passed:\nEnsemble pods correctly report ensemble size {captured_ensemble_size} and random weight {captured_random_weight}.')
        print(f'{pod_and_proc_count} processes were used to capture the pod logs.')
