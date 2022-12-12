/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

pipeline {
    agent none

    environment {
        UNITY_PROJECT_DIR='UnityProjectSample'
        IMAGE='unityci/editor'
        UNITY_VERSION='2020.2.4f1-ios-1.0'
        // Build parameters
        UNITY_LICENSE_FILE='UNITY_LICENSE_FILE'
        PROVISIONING_PROFILE_NAME='UnityBuildSample-profile'
        // secret from Secrets Manager
        TEAM_ID_KEY='TEAM_ID'
        LICENSE_SERVER_ENDPOINT='LICENSE_SERVER_ENDPOINT'
        SIGNING_CERT='SIGNING_CERT'
        SIGNING_CERT_PRIV_KEY='SIGNING_CERT_PRIV_KEY'
        SIGNING_CERT_PRIV_KEY_PASSPHRASE='SIGNING_CERT_PRIV_KEY_PASSPHRASE'
        APPLE_WWDR_CERT='APPLE_WWDR_CERT'
        PROVISIONING_PROFILE='PROVISIONING_PROFILE'
    }

    stages {
        
        stage('build Unity project on spot') {
            agent {

                docker {
                    image 'unityci/editor:2021.3.6f1-ios-1.0'
                    args '-u root:root'
                }
            }
            steps {
                // install stuff for Unity, build xcode project, archive the result
                sh '''
                printenv
                echo "===Installing stuff for unity" 
                apt-get update 
                apt-get install -y curl unzip zip
                curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" 
                unzip -o awscliv2.zip 
                ./aws/install 
                apt-get install sudo
                # Following section can be uncommented if Unity Build server is used
                # just to push it through
                # sudo mkdir -p /usr/share/unity3d/config/
                # endpoint=`aws secretsmanager get-secret-value \
                #     --secret-id $LICENSE_SERVER_ENDPOINT --output text --query 'SecretString' | cut -d '"' -f4`
                
                # configfile='{ 
                #     "licensingServiceBaseUrl": "'$endpoint'", 
                #     "enableEntitlementLicensing": true, 
                #     "enableFloatingApi": true, 
                #     "clientConnectTimeoutSec": 5, 
                #     "clientHandshakeTimeoutSec": 10 
                # }'
                # Copying Unity .ulf license file from S3 to container
                # aws s3 cp "s3://${S3_BUCKET}/Unity_2021.3.6f1-ios-1.0.ulf" "/root/.local/share/unity3d/Unity/Unity_lic.ulf"
                mkdir -p "/root/.local/share/unity3d/Unity"
                aws secretsmanager get-secret-value --secret-id $UNITY_LICENSE_FILE --output text --query SecretBinary |
                         base64 -d > "/root/.local/share/unity3d/Unity/Unity_lic.ulf"
                echo "===Building Xcode project" 
		# We also pull in additional repository with actual Unity Project. 
		# We have several configuration files for our build configuration 
		# You can find those in UnityProjectSample folder
                rm nodulus -rf
                git clone https://github.com/Hyperparticle/nodulus.git
                cp -nR nodulus/* UnityProjectSample/
                cd $UNITY_PROJECT_DIR
                mkdir -p ./iOSProj
                mkdir -p ./Build/iosBuild
                xvfb-run --auto-servernum --server-args='-screen 0 640x480x24' \
                    /opt/unity/Editor/Unity \
                    -quit \
                    -batchmode \
                    -nographics \
                    -executeMethod ExportTool.ExportXcodeProject \
                    -buildTarget iOS \
                    -customBuildTarget iOS \
                    -customBuildName iosBuild \
                    -customBuildPath ./Build/iosBuild \
                    -logFile /dev/stdout
                echo "===Zipping Xcode project"
                zip -r iOSProj iOSProj
                '''
                // pick up archive xcode project
                dir("${env.UNITY_PROJECT_DIR}") {
                    stash includes: 'iOSProj.zip', name: 'xcode-project'
                }
            }
            post {
                always {
                    sh "chmod -R 777 ."
                }
            }
        }
        stage('build and sign iOS app on mac'){
            // we don't need the source code for this stage
            options {
                skipDefaultCheckout()
            }
            agent {
                label "mac"
            }
            environment {
                HOME_FOLDER='/Users/jenkins'
                PROJECT_FOLDER='iOSProj'
            }
            steps {
                unstash 'xcode-project'
                sh '''
                pwd
                ls -l
                # Remove old project and unpack a new one
                rm -rf ${PROJECT_FOLDER}
                unzip iOSProj.zip
                '''

                // create export options file
                writeFile file: "${env.PROJECT_FOLDER}/ExportOptions.plist", text: """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>signingStyle</key>
                    <string>manual</string>
                </dict>
                </plist> 
                """

                sh '''
                PATH=$PATH:/usr/local/bin
                cd ${PROJECT_FOLDER}
                # Update project settings
                # sed -i "" 's|^#!/bin/sh|#!/bin/bash|' MapFileParser.sh
                # extra backslash for groovy
                TEAM_ID=`aws secretsmanager get-secret-value \
                    --secret-id $TEAM_ID_KEY --output text --query 'SecretString' | cut -d '"' -f4`
                # extra backslash for groovy
                sed -i "" "s/DEVELOPMENT_TEAM = \\"\\"/DEVELOPMENT_TEAM = $TEAM_ID/g" Unity-iPhone.xcodeproj/project.pbxproj
                #############################################
                # setup certificates in a temporary keychain
                #############################################
                
                echo "===Setting up a temporary keychain"
                pwd
                # Unique keychain ID
                MY_KEYCHAIN="temp.keychain.`uuidgen`"
                MY_KEYCHAIN_PASSWORD="secret"
                security create-keychain -p "$MY_KEYCHAIN_PASSWORD" "$MY_KEYCHAIN"
                # Append the temporary keychain to the user search list
                # double backslash for groovy
                security list-keychains -d user -s "$MY_KEYCHAIN" $(security list-keychains -d user | sed s/\\"//g)
                # Output user keychain search list for debug
                security list-keychains -d user
                # Disable lock timeout (set to "no timeout")
                security set-keychain-settings "$MY_KEYCHAIN"
                # Unlock keychain
                security unlock-keychain -p "$MY_KEYCHAIN_PASSWORD" "$MY_KEYCHAIN"
                echo "===Importing certs"
                # Import certs to a keychain; bash process substitution doesn't work with security for some reason
                aws secretsmanager get-secret-value --secret-id $SIGNING_CERT --output text --query SecretBinary |
                    base64 -d -o /tmp/cert &&
                    security -v import /tmp/cert -k "$MY_KEYCHAIN" -T "/usr/bin/codesign"
                rm /tmp/cert
                PASSPHRASE=`aws secretsmanager get-secret-value \
                    --secret-id $SIGNING_CERT_PRIV_KEY_PASSPHRASE --output text --query 'SecretString' | cut -d '"' -f4`
                aws secretsmanager get-secret-value --secret-id $SIGNING_CERT_PRIV_KEY --output text --query SecretBinary |
                    base64 -d -o /tmp/priv.p12 &&
                    security -v import /tmp/priv.p12 -k "$MY_KEYCHAIN" -P "$PASSPHRASE" -t priv -T "/usr/bin/codesign" 
                rm /tmp/priv.p12; PASSPHRASE=''
                #aws secretsmanager get-secret-value --secret-id $APPLE_WWDR_CERT --output text --query SecretBinary |
                #    base64 -d -o /tmp/cert &&
                #    security -v import /tmp/cert -k "$MY_KEYCHAIN"
                #rm /tmp/cert
                # Dump keychain for debug
                security dump-keychain "$MY_KEYCHAIN"
                # Set partition list (ACL) for a key
                security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k $MY_KEYCHAIN_PASSWORD $MY_KEYCHAIN
                # Get signing identity for xcodebuild command
                security find-identity -v -p codesigning $MY_KEYCHAIN
                # double backslash for groovy
                CODE_SIGN_IDENTITY=`security find-identity -v -p codesigning $MY_KEYCHAIN | awk '/ *1\\)/ {print $2}'`
                echo code signing identity is $CODE_SIGN_IDENTITY
                security default-keychain -s $MY_KEYCHAIN
                #############################################
                # setup provisioning profile
                #############################################
                
                echo ===setting up a provisioning profile
                pwd
                
                # # if the provisioning profile already exists, don't overwrite
                # PROV_PROFILE_FILENAME="${HOME}/Library/MobileDevice/Provisioning Profiles/${PROVISIONING_PROFILE_NAME}.mobileprovision"
                
                # if [ ! -f "$PROV_PROFILE_FILENAME" ]; then 
                #     aws secretsmanager get-secret-value --secret-id $PROVISIONING_PROFILE --output text --query SecretBinary |
                #         base64 -d -o "${PROV_PROFILE_FILENAME}"
                # fi
                # # lock, since multiple jobs can use the same provisioning profile
                # if [ -f "${PROV_PROFILE_FILENAME}.lock" ]; then
                #     n=`cat "${PROV_PROFILE_FILENAME}.lock"`
                #     n=$((n+1))
                # else
                #     n=1
                # fi
                # echo $n > "${PROV_PROFILE_FILENAME}.lock" 
                
                #############################################
                # Build
                #############################################
                echo ===Building 
                pwd
                # xcodebuild -scheme Unity-iPhone -sdk iphoneos -configuration AppStoreDistribution archive -archivePath "$PWD/build/Unity-iPhone.xcarchive" CODE_SIGN_STYLE="Manual" PROVISIONING_PROFILE_SPECIFIER_APP="$PROVISIONING_PROFILE_NAME" CODE_SIGN_IDENTITY=$CODE_SIGN_IDENTITY OTHER_CODE_SIGN_FLAGS="--keychain=$MY_KEYCHAIN" -UseModernBuildSystem=0
                xcodebuild -scheme Unity-iPhone -sdk iphoneos -configuration AppStoreDistribution archive -archivePath "$PWD/build/Unity-iPhone.xcarchive" CODE_SIGN_STYLE="Manual" CODE_SIGN_IDENTITY=$CODE_SIGN_IDENTITY OTHER_CODE_SIGN_FLAGS="--keychain=$MY_KEYCHAIN" -UseModernBuildSystem=0 CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO 
                # Generate ipa
                echo ===Exporting ipa
                pwd
                # xcodebuild -exportArchive -archivePath "$PWD/build/Unity-iPhone.xcarchive" -exportOptionsPlist ExportOptions.plist -exportPath "$PWD/build"
                
                
                #############################################
                # Upload
                #############################################
                # Upload to S3
                # /usr/local/bin/aws s3 cp ./build/*.ipa s3://${S3_BUCKET}/ 
                #############################################
                # Cleanup
                #############################################
                # Delete keychain - should be moved to a post step, but this would require a global variable or smth
                security delete-keychain "$MY_KEYCHAIN"
                # Delete a provisioning profile if no jobs use it anymore
                n=0
                if [ -f "${PROV_PROFILE_FILENAME}.lock" ]; then
                    n=`cat "${PROV_PROFILE_FILENAME}.lock"`
                    n=$((n-1))
                    echo $n > "${PROV_PROFILE_FILENAME}.lock"
                fi
                if [ "$n" -le "0" ]; then
                    rm -f "${PROV_PROFILE_FILENAME}"
                    rm -f "${PROV_PROFILE_FILENAME}.lock"
                fi
                '''
            }
            post {
                always {
                    sh '''
                    #############################################
                    # cleanup 
                    #############################################
                    zip -r iOSProj/build/Unity-iPhone.zip iOSProj/build/Unity-iPhone.xcarchive
                    '''
                    archiveArtifacts artifacts: '**/Unity-iPhone.zip', onlyIfSuccessful: true, caseSensitive: false
                }
            }
        }
    }
    post {
        success {
            echo 'Success ^_^'
        }
        failure {
            echo 'Failed :('
        }
    }
}