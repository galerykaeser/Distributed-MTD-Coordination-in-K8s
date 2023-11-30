# Semi-Random Leader Election for Distributed Moving Target Defense Coordination in Kubernetes

Author: Lucas Galery Käser

Welcome to the repository containing the source code for my [Master's thesis](https://umu.diva-portal.org/smash/record.jsf?aq2=%5B%5B%5D%5D&c=71&af=%5B%5D&searchType=LIST_LATEST&sortOrder2=title_sort_asc&query=&language=en&pid=diva2%3A1808601&aq=%5B%5B%5D%5D&sf=all&aqe=%5B%5D&sortOrder=author_sort_asc&onlyFullText=false&noOfRows=50&dswid=-6899).

In this thesis, I developed a distributed version of an existing coordinator for moving target defense (MTD) in Kubernetes.
I extended [Philip Tibom and Max Buck's work](https://github.com/ptibom/Moving-Target-Defense-with-Kubernetes/), who developed the initial prototype for coordinating MTD in Kubernetes.
This repository started as an unofficial fork of theirs.


## How to Run the Distributed MTD Coordinator
Running the distributed MTD coordinator in Kubernetes requires the following steps:
1. Build a container image from the source code using [Maven](https://maven.apache.org/) and [Jib](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin). From within the `MtdJava` directory, run the following:
 
    `mvn compile com.google.cloud.tools:jib-maven-plugin:3.3.1:dockerBuild -Djib.to.image=<IMAGE TAG> -Djib.container.args=<MTD SETTINGS YAML FILE>,<ENSEMBLE SIZE>,<RANDOM WEIGHT>`

    `<IMAGE TAG>` is the tag for the produced container image. `<MTD SETTINGS YAML FILE>` is a YAML file defining the settings for the MTD, it must be compatible with Tibom and Buck's MTD prototype, and it must be placed in the `MtdJava/src/main/resources` directory. For testing purposes, the file `DistMtdTestSettings.yaml` in the `MtdJava/src/main/resources` directory can be used. `<ENSEMBLE SIZE>` is the size of the MTD coordinator ensemble, it must match the YAML file used later for deployment. `<RANDOM WEIGHT>` must be a float between 0.0 and 1.0 and defines how random the leader election should be.
2. Push the container image to a desired repository using [Docker](https://www.docker.com/). Run the following:

    `docker push <IMAGE TAG>`
3. Modify one of the deployment descriptions in `MtdJava/src/main/resources` (files starting with `mtd-`) to your use-case. Change the image tag for the `mtd-coordinator` to the `<IMAGE TAG>` used previously. Make sure that the `replicas` field and the `--servers` argument match your ensemble size and that the `maxUnavailable` field is an integer strictly smaller than your ensemble size divided by two.
4. Deploy the prototype on your cluster by applying the deployment description with:

    `kubectl apply -f <DEPLOYMENT YAML FILE>`


## License (GPLv3)
This project is based on the project "Moving Target Defense with Kubernetes" by Philip Tibom and Max Buck, which is licensed under GPLv3. Thus, this project is also licensed under GPLv3.

Next follows a list of the changes made to the original project:
- `experiments/`: The directory and its entire content were added.
- `MtdJava/pom.xml`: The SLF4J dependency was removed and the Apache ZooKeeper dependency was added. The Jib Maven plugin was added.
- `MtdJava/src/main/java/`:
  - `MtdMain.java`: Support was added for the program arguments required by this project.
  - `controller/MtdController.java`: The signature of the `runMtd()` method was changed to `runMtd(int numRounds)` and an additional `runMtd()` method was added. The method `runMtdAsCandidate(int ensembleSize, double randomWeight)` was added.
  - `controller/algs/MtdRandomV3.java`: The methods `cleanupPreviousDeployments()`, `getPreviousDeployments()`, `cleanUpOldLabels()`, and `moveTarget()` were added. The `moveTarget()` method was based on the `run(int nSwaps)`. The `run(int nSwaps)` method was modified.
  - `leaderelection/`: The entire package was added and developed during this project.
  - `model/kubernetes/`:
    - `IDeployment.java` and `Deployment.java`: The methods `getDeploymentCounter()`, `setDeploymentCounter(int deploymentCounter)`, `trySetCounterFromName(String basename)`, `applyUnique()`, and `getRelatedDeployments(String namespace)` were added. In `Deployment.java`, the field `deploymentCounter` was added, and the `apply(int deploymentCounter)` method was modified.
    - `INode.java` and `Node.java`: The method `getAllocatableCpu()` was added. In `Node.java`, the `toString()` method was added.
    - `NodeTools.java`: The `getWorkerNodes()` method was modified.
- `MtdJava/src/main/resources/`: The following files were added:
  - `DistMtdDeploymentPrintNode.yaml`: Based on `DeploymentPrintNode.yaml` from the original project.
  - `DistMtdTestService.yaml`: Based on `TestService.yaml` from the original project.
  - `DistMtdTestSettings.yaml`: Based on `Test.yaml` from the original project.
  - `mtd-3-kind.yaml`
  - `mtd-3-microk8s.yaml`
  - `mtd-5-kind.yaml`
  - `mtd-5-microk8s.yaml`
- `.gitignore`: The following four elements were added:
  - `.idea/`
  - `MtdJava.iml`
  - `KubeJavaTest.iml`
  - `.mtd-env/`
- `README.md`: Apart from the original copyright notice, the file was entirely rewritten. 

Under `ORIGINAL_REPOSITORY`, the state of the original repository in the `master` branch at commit `2c047b1` from 1 November 2022 can be found, which served as the starting point for this project.

Below is the original copyright notice, as provided with Tibom and Bucks's project. One change has been made to it, namely a bugfix such that the URL at the end is displayed correctly.

<pre>
Moving Target Defense with Kubernetes
Copyright (C) 2022  Philip Tibom and Max Buck

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see &lt;https://www.gnu.org/licenses/&gt;.
</pre>
