import groovy.json.JsonSlurper

server = Artifactory.server "artifactory"
rtFullUrl = server.url

podTemplate(label: 'helm-template' , cloud: 'k8s' , containers: [
        containerTemplate(name: 'jfrog-cli', image: 'docker.bintray.io/jfrog/jfrog-cli-go:latest', command: 'cat', ttyEnabled: true) ,
        containerTemplate(name: 'helm', image: 'alpine/helm:latest', command: 'cat', ttyEnabled: true) ]) {

    node('helm-template') {
        stage('Build Chart & push it to Artifactory') {
           def id =  getLatestHelmChartBuildNumber(rtFullUrl)
            println id
            println getBuildDockerImageManifestChecksum(id)
        }
    }
}

//Utils


private executeAql(aqlString) {
    File aqlFile = File.createTempFile("aql-query", ".tmp")
    aqlFile.deleteOnExit()
    aqlFile << aqlString

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactorypass',
                      usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {

        def getAqlSearchUrl = "curl -u$USERNAME:$PASSWORD -X POST " + rtFullUrl + "/api/search/aql -T " + aqlFile.getAbsolutePath()
        echo getAqlSearchUrl
        try {
            println aqlString
            def response = getAqlSearchUrl.execute().text
            println response
            def jsonSlurper = new JsonSlurper()
            def latestArtifact = jsonSlurper.parseText("${response}")

            println latestArtifact
            return new HashMap<>(latestArtifact.results[0])
        } catch (Exception e) {
            println "Caught exception finding lastest artifact. Message ${e.message}"
            throw e as java.lang.Throwable
        }
    }
}


def getLatestHelmChartBuildNumber (server_url) {
    def aqlString = 'builds.find ({"name": {"$eq":"demo-helm-app-demo"}}).sort({"$desc":["created"]}).limit(1)'
    results = executeAql(aqlString)

    return results['build.number'];
}


def getBuildDockerImageManifestChecksum (build_number) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'artifactorypass', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        def getBuildInfo = "curl -u$USERNAME:$PASSWORD " + rtFullUrl + "/api/build/demo-helm-app-demo/$build_number"
        println getBuildInfo

        try {
            def buildInfoText = getBuildInfo.execute().text
            def jsonSlurper = new JsonSlurper()
            def buildInfo = jsonSlurper.parseText("${buildInfoText}")

            return buildInfo.buildInfo.modules[0].dependencies.find{it.id == "manifest.json"}.sha1
        } catch (Exception e) {
            println "Caught exception finding latest helm chart build number. Message ${e.message}"
            throw e
        }
    }
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