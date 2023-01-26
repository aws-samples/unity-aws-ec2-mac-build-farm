import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.buildCache
import jetbrains.buildServer.configs.kotlin.buildFeatures.provideAwsCredentials
import jetbrains.buildServer.configs.kotlin.buildSteps.ScriptBuildStep
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.projectFeatures.awsConnection
import jetbrains.buildServer.configs.kotlin.triggers.vcs
import jetbrains.buildServer.configs.kotlin.vcs.GitVcsRoot

version = "2022.10"

project {
    vcsRoot(UnityDemo)
    vcsRoot(Nodulus)

    buildType(BuildUnityProject)
    buildType(BuildIOSApp)

    buildTypesOrder = arrayListOf(BuildUnityProject, BuildIOSApp)

    params {
        // Enable the TeamCity build cache feature
        param("teamcity.internal.feature.build.cache.enabled", "true")

        // Set the default code sign identity (e.g. "Apple Development" or "Apple Distribution")
        param("AppleCodeSignIdentity", "Apple Distribution")
    }

    features {
        awsConnection {
            id = "AWSCredentialsConnectionUnity"
            name = "Amazon Web Services (AWS)"
            regionName = DslContext.getParameter("AWS_Region")
            credentialsType = static {
                accessKeyId = DslContext.getParameter("AWS_Access_Key_ID")
                secretAccessKey = DslContext.getParameter("AWS_Secret_Access_Key")
                stsEndpoint = "https://sts.${DslContext.getParameter("AWS_Region")}.amazonaws.com"
            }
        }
    }
}

object BuildUnityProject : BuildType({
    name = "Build Unity Project"

    artifactRules = "Nodulus/iOSProj => XcodeProject.zip"

    vcs {
        root(UnityDemo)
        root(Nodulus, "+:. => Nodulus")
    }

    steps {
        script {
            name = "Apply overrides and fixes to Nodulus project"
            scriptContent = "cp -r UnityProjectSample/. Nodulus"
        }

        script {
            name = "Get Unity license file"
            scriptContent = """
                aws secretsmanager get-secret-value \
                    --secret-id UNITY_LICENSE_FILE \
                    --output text \
                    --query SecretBinary | base64 -d > "Unity_lic.ulf"
            """.trimIndent()
        }

        script {
            name = "Run Unity build"
            scriptContent = """
                # Copy the license file to the correct location
                mkdir -p "/root/.local/share/unity3d/Unity"
                cp Unity_lic.ulf /root/.local/share/unity3d/Unity
                
                # Run the build
                unity-editor \
                    -quit \
                    -batchmode \
                    -nographics \
                    -executeMethod ExportTool.ExportXcodeProject \
                    -projectPath Nodulus \
                    -buildTarget iOS \
                    -customBuildName iosBuild \
                    -customBuildPath ./Build/iosBuild \
                    -logFile /dev/stdout
            """.trimIndent()
            dockerImage = "unityci/editor:2021.3.6f1-ios-1.0"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
        }
    }

    features {
        buildCache {
            name = "UnityPackages"
            rules = "Nodulus/Library"
            publish = true
            use = true
        }

        provideAwsCredentials {
            awsConnectionId = "AWSCredentialsConnectionUnity"
        }
    }

    requirements {
        contains("system.agent.name", "Linux-XLarge")
    }
})

object BuildIOSApp : BuildType({
    name = "Build iOS App"

    artifactRules = """
        Nodulus.ipa => Nodulus.zip
        build => Nodulus.zip
    """.trimIndent()

    params {
        param("env.LANG", "en_US.UTF-8")
        param("env.LC_ALL", "en_US.UTF-8")
    }

    vcs {
        root(UnityDemo, "+:fastlane")
    }

    triggers {
        vcs {
        }
    }

    steps {
        script {
            name = "Get signing certificate and provisioning profile from AWS Secrets Manager"
            scriptContent = """
                mkdir -p fastlane/tmp
                
                aws secretsmanager get-secret-value \
                    --secret-id SIGNING_CERT_PRIV_KEY \
                    --output text \
                    --query SecretBinary | base64 -d -o fastlane/tmp/private.p12
                
                aws secretsmanager get-secret-value \
                    --secret-id SIGNING_CERT \
                    --output text \
                    --query SecretBinary | base64 -d -o fastlane/tmp/signing.cer
                  
                aws secretsmanager get-secret-value \
                    --secret-id PROVISIONING_PROFILE \
                    --output text \
                    --query SecretBinary | base64 -d -o fastlane/tmp/UnityProject.mobileprovision
            """.trimIndent()
        }

        script {
            name = "Download Apple WWDR CA certificates"
            scriptContent = """
                curl https://developer.apple.com/certificationauthority/AppleWWDRCA.cer \
                    --output fastlane/tmp/AppleWWDRCA.cer
                    
                curl https://www.apple.com/certificateauthority/AppleWWDRCAG2.cer \
                    --output fastlane/tmp/AppleWWDRCAG2.cer
                    
                curl https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer \
                    --output fastlane/tmp/AppleWWDRCAG3.cer
                    
                curl https://www.apple.com/certificateauthority/AppleWWDRCAG4.cer \
                    --output fastlane/tmp/AppleWWDRCAG4.cer
                    
                curl https://www.apple.com/certificateauthority/AppleWWDRCAG5.cer \
                    --output fastlane/tmp/AppleWWDRCAG5.cer
                    
                curl https://www.apple.com/certificateauthority/AppleWWDRCAG6.cer \
                    --output fastlane/tmp/AppleWWDRCAG6.cer
            """.trimIndent()
        }

        script {
            name = "Run Fastlane build"
            scriptContent = """
                PROVISIONING_PROFILE=`aws secretsmanager get-secret-value \
                    --secret-id PROVISIONING_PROFILE \
                    --output text \
                    --query 'Name'`
                
                if [ -z "${'$'}PROVISIONING_PROFILE" ]; then
                    echo "Provisioning profile does not exist - generating an unsigned xcarchive"
                    
                    fastlane build_xcarchive
                else
                    echo "Provisioning profile exists - generating a signed IPA"
                    
                    TEAM_ID=`aws secretsmanager get-secret-value \
                        --secret-id TEAM_ID \
                        --output text \
                        --query 'SecretString' | cut -d '"' -f4`
                                    
                    APP_BUNDLE=`aws secretsmanager get-secret-value \
                        --secret-id APP_BUNDLE \
                        --output text \
                        --query 'SecretString' | cut -d '"' -f4`
                                    
                    PASSPHRASE=`aws secretsmanager get-secret-value \
                        --secret-id SIGNING_CERT_PRIV_KEY_PASSPHRASE \
                        --output text \
                        --query 'SecretString' | cut -d '"' -f4`
                                
                    # Decode provisioning profile (to get its name)
                    security cms -D -i fastlane/tmp/UnityProject.mobileprovision > fastlane/tmp/App.plist
                                
                    # Extract name from decoded provisioning profile
                    PROFILE_NAME=`/usr/libexec/PlistBuddy -c "Print :Name" fastlane/tmp/App.plist`
                    
                    fastlane build_ipa \
                        bundle_identifier:"${'$'}APP_BUNDLE" \
                        code_sign_identity:"%AppleCodeSignIdentity%" \
                        certificate_password:"${'$'}PASSPHRASE" \
                        provisioning_profile_name:"${'$'}PROFILE_NAME" \
                        team_id:"${'$'}TEAM_ID"
                fi
            """.trimIndent()
        }
    }

    features {
        provideAwsCredentials {
            awsConnectionId = "AWSCredentialsConnectionUnity"
        }
    }

    dependencies {
        dependency(BuildUnityProject) {
            snapshot {
            }

            artifacts {
                artifactRules = "XcodeProject.zip!** => ."
            }
        }
    }

    requirements {
        contains("system.agent.name", "Mac-Medium")
    }
})

object UnityDemo : GitVcsRoot({
    name = "UnityDemo"
    url = "https://github.com/aws-samples/unity-aws-ec2-mac-build-farm.git"
    branch = "refs/heads/main"
    branchSpec = "refs/heads/*"
    checkoutPolicy = AgentCheckoutPolicy.SHALLOW_CLONE
    authMethod = anonymous()
})

object Nodulus : GitVcsRoot({
    name = "Nodulus"
    url = "https://github.com/Hyperparticle/nodulus.git"
    branch = "refs/heads/master"
    branchSpec = "refs/heads/*"
    checkoutPolicy = AgentCheckoutPolicy.SHALLOW_CLONE
    authMethod = anonymous()
})
