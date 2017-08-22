import static groovy.io.FileType.FILES

def checkServerLogs = { mgr, wspath ->
    new File(wspath+"/nuxeo-distribution").eachFileRecurse(FILES) {
        if (it.name.equals("server.log")) {
            mgr.contains(it,'.*ERROR.*') {
                mrg.unstable()
            }
        }
    }
}

timeout(240) {
    node('ondemand') {
        try {
            timestamps {
                stage('checkout') {
                    checkout scm
                    sh "./clone.py ${env.BRANCH_NAME}"
                    currentBuild.description = env.BRANCH_NAME
                }
                stage('install') {
                    withMaven(
                        maven: 'maven-3',
                        mavenSettingsConfig: '51acdf6a-30c6-44d6-9390-b08bccb22b1d') {
                        sh "mvn -Pqa,itest install -fae -nsu -Dnuxeo.tests.random.mode=bypass"
                        warnings consoleParsers: [[parserName: 'Maven']], parserConfigurations: [[parserName: 'Java Compiler (javac)', pattern: 'nuxeo-distribution/**/log/*.log']]
                        archive excludes: '**/*.java', includes: '**/target/failsafe-reports/*, **/target/*.png, **/target/screenshot*.html, **/target/*.json, **/target/results/result-*.html, **/*.log, nuxeo-distribution/**/log/*, **/nxserver/config/distribution.properties, .ci-metrics'
                        junit allowEmptyResults: true, testResults: '**/target/failsafe-reports/*.xml, **/target/surefire-reports/*.xml, **/target/nxtools-reports/*.xml'
                        checkServerLogs mgr: manager, wspath: WORKSPACE
                        //TODO Ext email
                    }
               }
            }
        } catch(e) {
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
