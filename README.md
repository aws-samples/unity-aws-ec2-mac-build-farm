# Unity builds on EC2 Mac and Spot

## Overview

This is a sample Jenkins pipeline to build Unity applications on a mix of Amazon EC2 Spot Linux instances and [Amazon EC2 Mac instance](https://aws.amazon.com/ec2/instance-types/mac/) in the AWS Cloud.

This hybrid approach improves performance and reduces cost compared to building everything on EC2 Mac. The build happens in two stages: first, a Unity project is built on a scalable fleet of Linux Spot instances, then the resulting xcode project is finalized and signed on a single EC2 Mac instance if you have the Apple developer profile. Otherwise *.xcarchive* file is built which can be used for debugging on a local device and signed later.

## Deployment instructions

There's a CloudFormation template to provision required AWS resources and a guide to complete manual steps. See the [Lab](https://catalog.us-east-1.prod.workshops.aws/workshops/43e96ac6-6d4f-4d99-af97-3ac2a5987391/en-US/020-build-farms/060-labs-unity-mac) for details.

## Architecture
![Arch diagram](./Unity_build_pipeline.png)

Jenkins uses a manager node to orchestrate builds. Build jobs will run on both EC2 Mac instance and EC2 Spot instances managed by the EC2 Fleet plugin which in its turn is managed by Amazon Autoscaling group. Jenkins manager stores configuration, some artifacts, provides a user interface and orchestrates build jobs.

An EC2 instance for the Jenkins manager will be already created after you deploy Cloudformation stack in your AWS account. And Jenkins software will also be preinstalled. By following steps in Lab you just need to configure it. As well as launch EC2 Mac instance on a dedicated host, configure it as a Jenkins runner and include in an overall process.



