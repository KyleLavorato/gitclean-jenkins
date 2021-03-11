@Library('SharedLibrary') _

import groovy.json.JsonSlurperClassic

stage('Schedule Jobs') {
    node('aws&&docker') {
    try {
        writeFile(file:'gitclean.json', text:spgithub.getContents('gitclean/gitclean.json').content)
        def jsonFile = readFile('gitclean.json')
        def root = new JsonSlurperClassic().parseText(jsonFile)
        root.each { repo, fields ->
            def job = 'app/github-cleanup/master'
            build(
                job: job,
                propagate: false,
                wait: false,
                parameters: [
                    string(name: 'REPOSITORY', value: fields.repository),
                    string(name: 'ORG', value: fields.org),
                    string(name: 'SQUAD_CHANNEL', value: fields.squadChannel),
                    string(name: 'DAYS_FOR_PR_WARN', value: fields.daysForPrWarn),
                    string(name: 'DAYS_FOR_PR_CLOSE', value: fields.daysForPrClose),
                    string(name: 'DAYS_FOR_BOT_BRANCH_DELETE', value: fields.daysForBotBranchDelete),
                    string(name: 'DAYS_FOR_BRANCH_WARN', value: fields.daysForBranchWarn),
                    string(name: 'BRANCH_WARN_LEVEL', value: fields.branchWarnLevel),
                ]
            )
        }
    } finally {
		deleteDir()
	}}
}