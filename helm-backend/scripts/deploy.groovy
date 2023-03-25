#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
//define var for job
def TAG = env.tag
def apptype = env.apptype
def profile = env.profile
def ECR_REPO = env.ECR_REPO

def git_clone() {
   stage name: 'app clone repo', concurrency: 5
   checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:elarahq/'+GIT_REPO+'.git', credentialsId: 'b5b5b230-4f8a-4213-a6ba-7efccc0ae00c' ]], branches: [[name: TAG]]], poll: false
}

def build() {
  stage name: 'build docker', concurrency: 5
  try {
     sh '''
     #!/bin/bash
     echo "we login to ecr"
     a=`/usr/bin/aws ecr get-login --region ap-southeast-1`
     login=`echo $a | sed -e "s/none//g" | sed -e "s/-e//g"`
     $login
     '''
      sh "java -version"
      sh "export JAVA_HOME=/opt/openlogic-openjdk-11.0.11+9-linux-x64"
      sh "export PATH=$PATH:/opt/openlogic-openjdk-11.0.11+9-linux-x64/bin"
     sh "/home/jenkins/apache-maven-3.8.1/bin/mvn clean deploy -P${profile}"
     println "++++++++++++++++++docker build done+++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with mvn")
  }
}

 def argo_clone() {
    stage name: 'clone argo repo', concurrency: 5
    dir('argocode') {
      git ([url: 'git@github.com:elarahq/housing.argo.git', branch: 'master', changelog: true, poll: true])
    }
}
def updateimage() {
  stage name: 'updatetask in git-file', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    cd ${WORKSPACE}/argocode/apps/overlays/${env_name}/${APP_NAME}/
    oldimage=`cat kustomization.yaml | grep newTag | awk '{print$2}'`
    echo "old image in kustomization.yaml"
    echo $oldimage
    echo "replcaing in file"
    echo $newtag
    sed -e "s#${oldimage}#${newtag}#g" kustomization.yaml
    sed -i "s#${oldimage}#${newtag}#g" kustomization.yaml
    '''
    println "++++++++++++++++done++++++++++++++++"
  } catch (all) {
    throw new hudson.AbortException("Some issue with  image update in  git file")
  }
 }
def clean_artifact() {
  stage name: 'clean image', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    echo "we are deleting images"
    docker rmi -f $(docker images -a -q)
   '''
  } catch (all) {
    println "++++++++++++++++no docker to  delete++++++++++++++++"
  }
}
def argosync(){
  stage name:'update app version'
       sh '''
       export ARGOCD_SERVER="gamma-argocd.housing.com"
       /usr/local/bin/argocd --grpc-web app  set $APP_NAME --kustomize-image housing-image=${imagetag} --path apps/overlays/${env_name}/${APP_NAME}
       /usr/local/bin/argocd --grpc-web app  sync $APP_NAME
       '''
}
def sync(){
  stage name:'rotate pod have same tag'
       sh '''
       export ARGOCD_SERVER="gamma-argocd.housing.com"
       buildtime=`date +"%Y-%m-%dT%H:%M:%S""Z"`
       echo $buildtime
       /usr/local/bin/argocd --grpc-web app patch-resource  $APP_NAME  --kind Deployment --resource-name ${env_name}-${APP_NAME}-housing --patch '{"spec": { "template":{ "metadata": { "creationTimestamp": "'${buildtime}'"} }}}' --patch-type 'application/strategic-merge-patch+json'
       '''
}
def updategit() {
  stage name: 'commit in git', concurrency: 5
  try {
    sh '''
    #!/bin/bash
    cd ${WORKSPACE}/argocode
    /usr/bin/git add apps/overlays/${env_name}/${APP_NAME}/kustomization.yaml
    /usr/bin/git commit -m "jenkins"
    /usr/bin/git push origin master
    '''
    println "++++++++++++++++done++++++++++++++++"
  } catch (all) {
    println "++++++++++++++++nothing to commit++++++++++++++++"
  }
 }

node("dev-mini-housing-jenkins-slave") {
    if("${env.reload}" == "true"){
      withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       sync()
      }
     } else {
      env.newtag="${TAG}"
      env.imagetag="628119511333.dkr.ecr.ap-southeast-1.amazonaws.com/housing/${ECR_REPO}:${TAG}"
      git_clone()
      build()
      withCredentials([string(credentialsId: "argo", variable: 'ARGOCD_AUTH_TOKEN')]) {
       argosync()
      }
      argo_clone()
      updateimage()
      updategit()
      clean_artifact()
     }
 }
