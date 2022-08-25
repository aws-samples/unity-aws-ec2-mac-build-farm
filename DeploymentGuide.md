# Deployment guide

> This is a work in progress draft. It describes all necessary steps, but might have some inaccuracies or lack some details.

## Introduction

This guide describes steps to deploy a Jenkins pipeline to build the sample Unity game on a mix of EC2 Spot Linux instances and EC2 Mac.

There is a CloudFormation template that deploys basic infrastructure (VPC, security groups, IAM roles, instances, ECR registry, etc) and a series of manual steps to upload the sample code, docker images and to configure Jenkins and EC2 Mac. We did not include these manual steps into the CloudFormation template for two reasons: first, not all of them can be automated easily (like accepting EULA or providing customer-owned certificates), second, running them manually helps to understand the architecture better.

After you have run all the steps from the guide, you will have the following resources created in your account:
- a VPC with two public and two private subnets, an Internet gateway and a NAT gateway
- necessary security groups, IAM policies and IAM roles
- four EC2 Linux instances:
    - bastion host (t3.nano). It's used to connet to Jenkins controller and EC2 Mac from the Internet
    - Jenkins controller (t3.small)
    - Unity Build Server (t3.small). It vends Unity licenses to build agents
    - one build agent running on Linux Spot (c5.4xlarge) in an Auto-Scaling group
- a dedicated mac1.metal host and a mac1.metal EC2 Mac instance running on it
- ECR registry to store Unity docker images

Completion time - **TBD**. The CloudFormation template deployment takes **under 10 minutes**. It will take another 5-10 minutes to finish the Mac instance initialization, however there is no need to wait for it to proceed with the workshop. Need to estimate time for manual steps. I think, it will be around 1 hour overall.

An estimated daily cost of the deployed solution is [$35](https://calculator.aws/#/estimate?id=9471ca902c86e6c232238d97f705996e87a0963b). EC2 Mac is the most expensive part and comprises over 80% of the cost. Running more than one concurrent build can lead to spinning up more than one Linux Spot workers (up to 3 at maximum) and can increase cost accordingly. Running many builds can also incur some small extra cost for data transfer and other services usage.
> :exclamation: Please note that you won't be able to release an EC2 Mac dedicated host earlier than 24 hours after its creation. This means you'll be charged for at least 24 hours once you deployed it.


## Prerequisites

1. Paid Apple Developer account. You need it to create signing certificates and provisioning profiles on the developer portal and use them with the manual code signing in xcode.
2. Unity license.  You can run this pipeline in production and as a personal setup for testing. For production you need a Unity Build server license.
    - Get a Unity Pro or Unity Enterprise subscription here: https://store.unity.com/compare-plans
    - Subscribe to Build server license here: https://unity.com/products/unity-build-server

## Deploy CloudFormation stack

Deploy infrastructure (VPC, subnets, instances, security groups, IAM roles, etc) with CloudFormation. Provide a valid keypair that you have access to, you will need it later to connect to your resources.
> :exclamation: The CloudFormation template works in us-east-2 only at the moment

## Setup the solution

This sections describes manual steps.

### Prepare a source code repo

> can take over 15 min on 5-7 MB/s connection, is there a faster way? Also, the repo size is 4.5 GB, and the sample is only 2 GB, need to clean up

Follow steps described in this article: https://docs.aws.amazon.com/codecommit/latest/userguide/how-to-migrate-repository-existing.html
or follow the short version (with https credentials):
1. Create a repository in CodeCommit:
    ``` 
    aws codecommit create-repository --repository-name aws-unity-build-pipeline
    ```
2. Create CodeCommit credentials: 
    - Go to IAM -> Users -> Choose your user - _what if I'm using a role?_
    - Select tab "Security credentials"
    - Under "HTTPS Git credentials for AWS CodeCommit" click "Generate credentials"
    - Click "Download Credentials"
3. Clone repository locally: 
    ```
    git clone --mirror https://github.com/{this_repo_path}.git aws-unity-build-pipeline
    ```
4. Change the directory:
    ```
    cd aws-unity-build-pipeline
    ```
5. Run the git push command, specifying the URL and name of the destination CodeCommit repository and the --all option.
    ```
    git push https://git-codecommit.us-east-2.amazonaws.com/v1/repos/aws-unity-build-pipeline --all
    ```
6. Open your CodeCommit repository in the AWS console and make sure that the files were uploaded.

### Prepare Unity container images

In order to achieve maximum flexibility with Unity versions, the best way is to use Unity within a Linux docker container. 
You could build one yourself. 
Example steps would be: 
- Create a base image with required dependencies and sound card turned off
- Create Unity hub container out of base image using UnityHub AppImage from here: https://public-cdn.cloud.unity3d.com/hub/prod/UnityHub.AppImage
- Create Unity editor container and install unity editor using unityhub CLI
    ```
    RUN unity-hub install --version "$version" --changeset "$changeSet"
    ```
We will obtain a required version from https://hub.docker.com/r/unityci/editor/tags?page=1&ordering=last_updated instead. The project URL is: https://game.ci/

We will also need to have the container in ECR repository

In order to achieve it, steps are as following:
1. Get ECR repository name from a CloudFormation stack output called ECRRegistry. The name should be **aws-unity-build**
2. Push the Unity docker image to the repository:
    - Go to https://hub.docker.com/r/unityci/editor/tags?page=1&ordering=last_updated and find required version of Unity. In our example we will be using **2020.2.6f1-ios-0** 
    - In your CLI enter first line that should look like the following: 
        ```
        aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin <AWS Account ID>.dkr.ecr.us-east-2.amazonaws.com
        ```
    - Pull remote image locally: docker pull unityci/editor:2020.2.6f1-ios-0 - _docker daemon would be needed; need to provide instructions on installation or provide working enviroment_
    - *(Optional)* If using Unity Personal or Unity Plus/Pro/Eneterprize license, follow steps to activate Unity here: https://game.ci/docs/gitlab/activation
    - Tag the image: 
        ```
        docker tag unityci/editor:2020.2.6f1-ios-0 <ECRRegistry>.dkr.ecr.us-west-2.amazonaws.com/aws-unity-build:2020.2.6f1-ios-0
        ```
    - Push the image to the repository: 
        ```
        docker push <AWS Account ID>.dkr.ecr.us-west-2.amazonaws.com/aws-unity-build:2020.2.6f1-ios-0
        ```

3. Update a jenkinsfile:
    - Open Jenkinsfile. Find the line **UNITY_VERSION=**
    - Replace the value after '=' to docker image tag that you are using. In our case the final line would look like this:
        ```
        UNITY_VERSION='2020.2.6f1-ios-0'
        ```
### Setup Unity Build Server

After you have run Cloudformation template, your AWS environment should already have a server that will act as a Unity Build server (License server). However several steps are still required for it to work. Unity provides Build server executables and detailed instructions when you obtain Build server licenses.

Once you obtain the Unity build server license subscription, the steps are as following: 

1. Go to “Organizations” and choose your organization. 
2. Find your build license subscription. In case of “Pro”, the link says “Pro Build License”. Click this link.
3. On a build license page choose “Configure License Server”.
4. Click “Download new server”. You will be provided with server installation files.
5. Click “Documentation: **Linux**” (or **Windows**) to get Installation and configuration Manual.
6. SSH to your server (or RDP if you are using windows machine) upload server executable and follow the installation guide.
7. When going through setup phase, you will have to answer several questions one of which would ask about Network interface ex.
    ```
    List of available network interfaces on this host
    en0 (8C:85:90:CA:72:DC) 192.168.0.51
    gpd0 (02:50:41:00:01:01) 10.1.4 2228
    Enter the index number of the network interface which server will operate on
    ```
    Choose non-default interface - [2]. This way we ensure that even if machine will stop, we can reuse the interface for another machine as Unity Build server uses Interface Mac address.
8. After setup is done, exchange the server-registration-request.xml with License package as per instruction and register the license package with Build server
9. You will also be provided with “services-config.json” file that is supposed to be copied to every worker machine
10. Upload this file to s3 bucket of your choice
11. Update jenkinsfile
    - Find the line 
        ```
		sudo aws s3 cp s3://unitybuildmactest/services-config.json /usr/share/unity3d/config/services-config.json
		```
    - Replace with 
        ```
        sudo aws s3 cp s3://{yours3bucketname}/services-config.json /usr/share/unity3d/config/services-config.json
        ```

### Store secrets in AWS Secrets Manager

Create a development or provisioning certificate and a provisioning profile on the Apple Developer Portal and download them to your computer. You need a paid Apple Developer account for that. You'll need three files: a development/provisioning certificate, a private key that you used to create the cert and a provisioning profile.

Add them as secrets to the AWS Secrets Manager. Check environment section of the Jenkinsfile for secrets names. Basically, you'll need the following ones:
- SIGNING_CERT - a development certificate (development.cer)
- SIGNING_CERT_PRIV_KEY - a private key that you used to request it (priv.p12)
- SIGNING_CERT_PRIV_KEY_PASSPHRASE - a private key passphrase
- PROVISIONING_PROFILE - a provisioning profile for your app

Example CLI commands to store secrets in AWS Secrets Manager (use your file names and passphrase):
    
    aws secretsmanager create-secret --name SIGNING_CERT --description "Signing certificate" --secret-binary fileb://development.cer
    aws secretsmanager create-secret --name SIGNING_CERT_PRIV_KEY --description "Signing certificate private key" --secret-binary fileb://priv.p12
    aws secretsmanager create-secret --name SIGNING_CERT_PRIV_KEY_PASSPHRASE --description "Signing certificate private key passphrase" --secret-string "[YOUR PASSPHRASE]"
    aws secretsmanager create-secret --name PROVISIONING_PROFILE --description "Provisioning profile" --secret-binary fileb://[provisioning profile filename]


### Setup EC2 Mac
Mac setup can be completed through CLI via an SSH or Systems manager connection, however, this would require downloading software packages from your Apple Developer Account in advance. In this manula we will set up graphical remote accesss and will use it to install xcode and necessary prerequisites for simplicity.

#### Enable graphical remote desktop (VNC)
1. Connect through AWS Systems Manager Session Manager. Pick your EC2 Mac instance in AWS console, click Connect -> Session Manager -> Connect
2. Set up a password for ec2-user (run on Mac)
    ```
    sudo passwd ec2-user
    ```
3. Enable remote desktop access (run on Mac)
    ```
    sudo /System/Library/CoreServices/RemoteManagement/ARDAgent.app/Contents/Resources/kickstart \
    -activate -configure -access -on \
    -restart -agent -privs -all
    ```
4. Establish a SSH tunnel. :exclamation: Run this on you computer, not on Mac
    ```
    ssh -i <key> -L 5900:<MacHost>:5900 ec2-user@<BastionHost>
    ```
Use a key from the keypair that you provided during the CloudFormation stack deployment. Bastion and mac host names can be taken from the CloudFormation stack's outputs.
5. Connect from your computer:
- Mac: Finder -> Command+K -> vnc://localhost from Mac
- Linux/Windows: use VNC client
Login - ec2-user, password - the one that you created in step 2

Check EC2 Mac [documentation](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-mac-instances.html#mac-instance-vnc) for more info

#### Install Java
In the VNC session
1. Open Terminal (Launchpad icon in the Dock -> Other -> Terminal)
1. Install openjdk from brew
    ```
    brew install openjdk
    ```
2. Setup macOS to use the installed JDK
    ```
    sudo ln -sfn /usr/local/opt/openjdk/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk.jdk
    ```
3. Check that java was installed successfully
    ```
    java -version
    ```
    you should see something similar too
    ```
    openjdk version "16.0.2" 2021-07-20
    OpenJDK Runtime Environment Homebrew (build 16.0.2+0)
    OpenJDK 64-Bit Server VM Homebrew (build 16.0.2+0, mixed mode, sharing)
    ```


#### Install Xcode and accept license
1. Open App Store and search for Xcode
2. Click GET, INSTALL. You will need to provide your Apple ID, password and one-time confirmation code.
3. Check the installation progress on the Launchpad.
> :exclamation: Xcode installation can take up to 20-30 minutes, you can go ahead and setup Jenkins. You will need the Xcode only to actually run your build. Don't forget to accept an Xcode license before running a build.
4. Open Xcode and accept a license agreement

#### Disable VNC for security reasons - optional
You can disable VNC access now for security reasons if you want, it's not required for the rest of the workshop running builds.

#### Create a golden AMI to use later - optional
You can take a snapshot from EC2 Mac's EBS root volume, make a golden AMI out of the snapshot and use it later to quickly spin up more EC2 Mac instances.

### Setup Jenkins

(I also have some screenshots, will add them later)

#### Finalize installation 
1. Setup a SSH tunnel through the bastion host
    ```
    ssh -i <key> -L 8080:<JenkinsHost>:8080 ec2-user@<BastionHost>
    ```

1. Open http://127.0.0.1:8080 with a browser to complete Jenkins installation
    1. Obtain a Jenkins password. For that use AWS Systems Manager Session Manager to connect to the jenkins-manager instance (find it in EC2 console, it's called build/jenkins-manager, click Connect -> Session Manager) and run `sudo cat /var/lib/jenkins/secrets/initialAdminPassword` on it.
    1. Click "Install suggested plugins"
    1. Create Jenkins user and password, you will use them from now on to login to Jenkins UI 
    1. Leave default URL, click Save and Finish, click Start using Jenkins

1. Disable building on the manager node. Select Manage Jenkins in the left menu, click "Manage Nodes and Clouds", select Built-In Node, click Configure in the left menu. Set the Number of executors to 0. Click Save.

1. Install required plugins
    1. EC2 Fleet. Go back to Dashboard. Select Manage Jenkins in the left menu -> Manage Plugins -> Available, search for EC2 Fleet, select a check-box and click Install without restart. This plugin manages build agents on EC2 Spot instances in an AWS Auto-Scaling Group. 
    1. ECR Plugin - I don't think we are actually using it, will check later

#### Setup Linux Spot build agent 
We will use the EC2 Fleet Jenkins plugin and SSH to dynamically provision Jenkins agents on Spot in an auto-scaling group.

1. Manage Jenkins -> Manage Nodes and Clouds -> Configure Clouds -> unfold Add a new cloud -> choose Amazon EC2 Fleet
1. Use LinuxSpot for Name
1. Set up AWS Credentials
    - Click Add -> Jenkins. Select Kind -> AWS Credentials, scroll to the bottom, click Advanced under IAM Role Support. Paste ARN of jenkins-manager IAM role (you can get it from CloudFormation output JenkinsManagerRole) to the "IAM Role To Use" filed, leave eveythign else default, click Add
    - Select you region (us-east-2)
    - Select your auto-scaling group (it is named <...>-JenkinsInstances-<...>-SpotWorkersASG-<...>) under EC2 Fleet
    - Click Test Connection button, it should say "Success! Skipping validation for following permissions: TerminateInstances, UpdateAutoScalingGroup"
1. Setup a Launcher. This defines how Jenkins install its agents onto EC2 instances.
    - Select "Launch agents via SSH" under Launcher
    - Add credentials, Jenkins, SSH Username with private key. ID - jenkins-SSH, Username - ec2-user (or jenkins?), paste your private key (the one from the keypair you selecdted during the CloudFormation template deployment), click Add
    - Select ec2-user under Credentails
    - Key verification strategy - Non verifying Verification Strategy. TBD - do we need host key verification for dynamic workers?
    - Select Private IP checkbox
1. Minimum cluster size 1, Maximum cluster size 3
1. Leave everything else default, click Save
1. Verify that the worker has been added. For that select Dashboard in the left menu and check that an instance is listed under BuildExecutor Status without a red cross icon. Click on it for more details and logs. 

#### Setup Mac build agent
We will setup a single EC2 Mac as a permanent build agent via SSH
1. Manage Jenkins -> Manage Nodes and Clouds -> New Node
1. Node Name - Mac, pick Permanent Agent
1. Number of executors - 2
1. Remote root directory - /Users/ec2-user, this is where Jenins will store its files
1. Labels - mac; Usage - Only build jobs with label expressions matching this node. This is important, we use this label in Jenkins file to run the second build stage on Mac, and not on Linux Spot.
1. Launch method - Launch agents via SSH
    - Host - your mac hostname (you can get from CloudFormation stack outputs)
    - You can re-use the same credentials that use created for Linux workers, since the username and key are the same
    - Host Key Verification Strategy - Non verifying Verification Strategy
1. Leave everything else default and click Save
1. Verify that the worker has been added. For that select Dashboard in the left menu and check that an instance is listed under BuildExecutor Status without a red cross icon. Click on it for more details and logs. 


#### Create a build job
Now Jenkins setup is completed, we have build agents running and are ready to create a build job.

1. Create a job - multi-branch pipeline

Check authentication settings (ok by default in what I tested)


## Run a build

## Troubleshooting

The build pipeline can be troubleshooted in number of ways.
To check the build workers logs, use Jenkins pipeline logs. This way you obtain information from both mac and EC2 Spot instances. Build errors will be shown there.

To check build server status, you can call http://SERVER-IP-ADDRESS:PORT/v1/admin/status to see what is the current status.

You can also check cloudwatch monitoring to see how much resources your workers consume.


