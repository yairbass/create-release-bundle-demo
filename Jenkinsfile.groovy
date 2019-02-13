server = Artifactory.server "artifactory"
rtFullUrl = server.url

podTemplate(label: 'helm-template' , cloud: 'k8s' , containers: [
        containerTemplate(name: 'jfrog-cli', image: 'docker.bintray.io/jfrog/jfrog-cli-go:latest', command: 'cat', ttyEnabled: true) ,
        containerTemplate(name: 'helm', image: 'alpine/helm:latest', command: 'cat', ttyEnabled: true) ]) {

    node('helm-template') {
        git url: 'https://github.com/eladh/create-release-bundle-demo.git', credentialsId: 'github'
        def pipelineUtils = load 'pipelineUtils.groovy'

        stage('Build Chart & push it to Artifactory') {
           def id =  getLatestHelmChartBuildNumber(rtFullUrl)
            println id

        }
    }
}

//Utils


def getLatestHelmChartBuildNumber (server_url) {
    def aqlString = 'builds.find ({"name": {"$eq":"demo-docker-app-demo"}}).sort({"$desc":["created"]}).limit(1)'
    results = pipelineUtils.executeAql(aqlString)

    return results['build.number'];
}



def createDemoAppRelaseBunlde (sourceArtifactoryId, chartVersion, dockerVersion, distribution_url) {
    def aqlhelmString = "items.find({\\\"repo\\\":\\\"helm-local\\\",\\\"name\\\":\\\"" + chartVersion + "\\\"})"
    def aqlDockerString = "items.find({\\\"repo\\\":\\\"docker-local-prod\\\",\\\"name\\\":\\\"" + dockerVersion + "\\\"})"
    def releaseBundle = """ {
      "name":"helm-demo-app-bundle",
      "version": "${chartVersion}",
      "description":"Sample Docker App build",
      "dry_run":"false",
      "spec": {
            "source_artifactory_id": "$sourceArtifactoryId",
              "queries":[
              {
                 "aql": "${aqlhelmString}"
              },
              {
                 "aql": "${aqlDockerString}"
              }]
       }
    }"""

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactorypass', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        def rbdnRequest = ["curl", "-X", "POST", "-H", "Content-Type: application/json", "-d", "${releaseBundle}", "-u", "$USERNAME:$PASSWORD", "${distribution_url}release_bundle"]

        try {
            def rbdnResponse = rbdnRequest.execute().text
            println "Release Bundle Response is: " + rbdnResponse
        } catch (Exception e) {
            println "Caught exception finding latest docker-multi-app helm chart. Message ${e.message}"
            throw e
        }
    }

}