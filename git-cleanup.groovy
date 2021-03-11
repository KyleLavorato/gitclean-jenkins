@Library('SharedLibrary') _

import java.text.SimpleDateFormat

properties([
    parameters([
        stringParam(name: 'REPOSITORY', defaultValue: '', description: 'Repository to run the cleanup in'),
        stringParam(name: 'ORG', defaultValue: 'deep-security', description: 'Organization that the repository belongs to'),
        stringParam(name: 'SQUAD_CHANNEL', defaultValue: '', description: 'Name of the slack channel for the team the repository belongs to (Do not include the `#`)'),
        stringParam(name: 'DAYS_FOR_PR_WARN', defaultValue: '7', description: 'Age in days until a PR triggers a warning'),
        stringParam(name: 'DAYS_FOR_PR_CLOSE', defaultValue: '14', description: 'Age in days until a PR is closed'),
        stringParam(name: 'DAYS_FOR_BOT_BRANCH_DELETE', defaultValue: '0', description: 'Age in days until a bot created branch is deleted. Set to `0` to disable'),
        stringParam(name: 'DAYS_FOR_BRANCH_WARN', defaultValue: '90', description: 'Age in days until a branch is considered stale'),
        stringParam(name: 'BRANCH_WARN_LEVEL', defaultValue: '@daily', description: "How often to send out the stale branch warnings. Must be one of: '@daily', '@weekly', '@biweekly', '@monthly'")
    ])
])

// If the email used for GitHub commits differs from the one used in
// Slack, the mismatch can be resolved with an entry here so Slack
// messages can be sent.
// <GitHub Email>: <Slack Email>
def slackEmailAlias = [
    'firstname_lastname@example.com': "f.lastname@example.com",
]

// List of email addresses that bot users use to commit
def commitBotUser = "auto@example.com"
def botUsers = [
    "github-noreply@example.com",
    "noreply@example.com",
    "versionbump@example.com" ,
    // This email is from jenkins creating a merge commit for PRs
    "nobody@nowhere",
]

def slackPrReport = [:]
def slackBranchReport = [:]
def addToReport(report, usr, slackMsg) {
    if (report.containsKey(usr)) {
        report["${usr}"].add(slackMsg)
    } else {
        report.put(usr, [slackMsg])
    }
}

def repository = params.REPOSITORY
def org = params.ORG
def squadChannel = params.SQUAD_CHANNEL
def daysForPrWarn = params.DAYS_FOR_PR_WARN.toInteger() // Number of days old a PR must be to trigger a warning
def daysForPrClose = params.DAYS_FOR_PR_CLOSE.toInteger() // Number of days old a PR must be to be closed
def daysForBotBranchDelete = params.DAYS_FOR_BOT_BRANCH_DELETE.toInteger() // Number of days old a branch made by a bot must be to delete it
def daysForBotBranchWarn = daysForBotBranchDelete.intdiv(2) // Number of days until a warning is triggered that a bot branch is going to be deleted
def daysForFinalBotBranchWarn = daysForBotBranchDelete - 1 // Number of days until final warning is triggered that a bot branch is going to be deleted
def daysForBranchWarn = params.DAYS_FOR_BRANCH_WARN.toInteger() // Number of days old a branch must be to trigger a warning
// Branch warning level can be one of three options (Note: This does not impact bot branches)
//  @daily - Will slack users about their stale branches every day
//  @weekly - Will slack users about their stale branches every Wednesday
//  @biweekly - Will slack users about their stale branches on the 1st and 14th of every month
//  @monthly - Will slack users about their stale branches on the 1st of every month
def branchWarnLevel = params.BRANCH_WARN_LEVEL
if (branchWarnLevel != "@daily" && branchWarnLevel != "@weekly" && branchWarnLevel != "@biweekly" && branchWarnLevel != "@monthly") {
    error "BRANCH_WARN_LEVEL is not set to one of the four options"
}
if (repository == "") {
    currentBuild.description = "ERROR: Missing REPOSITORY"
    error "Please enter a value for REPOSITORY"
}
if (org == "") {
    currentBuild.description = "ERROR: Missing ORG"
    error "Please enter a value for ORG"
}
if (squadChannel == "") {
    currentBuild.description = "ERROR: Missing SQUAD_CHANNEL"
    error "Please enter a value for SQUAD_CHANNEL"
}
if (params.DAYS_FOR_PR_WARN == "" || params.DAYS_FOR_PR_CLOSE == "" || params.DAYS_FOR_BOT_BRANCH_DELETE == "" || params.DAYS_FOR_BRANCH_WARN == "") {
    currentBuild.description = "ERROR: Missing DAYS_FOR parameter"
    error "Please enter a value for all of the DAYS_FOR parameters"
}

currentBuild.displayName = "#${env.BUILD_NUMBER}: ${repository}-${branchWarnLevel}"

def currentDate = new Date()

// @weekly - Determine if we need to send a branch slack message
Calendar calToday = Calendar.getInstance()
calToday.setTime(currentDate)
boolean isWednesday = calToday.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY

// @biweekly - Determine if we need to send a branch slack message
boolean isFirstOrFourteenth = (currentDate.date == 1 || currentDate.date == 14)

// @monthly - Determine if we need to send a branch slack message
boolean isFirst = (currentDate.date == 1)

// Evaluated send slack condition
boolean sendBranchSlack = (
    (branchWarnLevel == '@daily') ||
    (branchWarnLevel == '@weekly' && isWednesday) ||
    (branchWarnLevel == '@biweekly' && isFirstOrFourteenth) ||
    (branchWarnLevel == '@monthly' && isFirst))

// Logs to be attached to the Jenkins run
def prLogs = ""
def branchLogs = ""

def splitLongMessage(String msg, int maxSize) {
    def messageSet = []
    while (msg.length() > maxSize) {
        def segment = msg.substring(0, maxSize - 1)
        def dotIndex = segment.lastIndexOf('•')
        segment = segment.substring(0, dotIndex)
        msg = msg.substring(dotIndex)
        messageSet.add(segment)
    }
    messageSet.add(msg)
    return messageSet
}

def sendSlackMessage(slackUsr, blocks) {
    // Ensure that the credential for the slack user bot exists on
    // the shard that we are running on. Otherwise the slack send
    // will silently fail.
    try {
        withCredentials([string(credentialsId: 'jenkinsbot-token', variable: 'SLACK_TOKEN')]) {
            echo "Found slack credentials"
        }
    } catch(err) {
        error("""[slackSend] ERROR: could not load credentialsId: 'jenkinsbot-token'.
        Make sure that:
            1. The credential exists
            2. The credential is of type 'Secret text'
            3. This build is in scope for that credential
        """)
    }
    // If sending a message to a slack channel, the `Jenkinsbot` app
    // must be added to the channel, otherwise it cannot send. It is
    // able to DM anyone without extra setup.
    slackSend([
        // failOnError will only fail on certain kinds of errors, but we might as
        // well get what we can
        failOnError: true,
        teamDomain: 'slackorg',
        tokenCredentialId: 'jenkinsbot-token',
        botUser: true,
        channel: slackUsr,
        blocks: blocks,
    ])
}

timeout(20) {
stage('Scan Repository') {
    SimpleDateFormat sameDayFmt = new SimpleDateFormat("yyyyMMdd") // Date format to compare based on the `day` field
    def warnPrDate = currentDate.minus(daysForPrWarn) // The date that we warn a PR is about to be closed
    def stalePrDate = currentDate.minus(daysForPrClose) // The date that we close an open PR
    def deleteBotBranchDate = currentDate.minus(daysForBotBranchDelete) // The date that we delete a bot created branch
    def warnBotBranchDate = currentDate.minus(daysForBotBranchWarn) // The date of warning for a bot created branch
    def warnBotBranchDateFinal = currentDate.minus(daysForFinalBotBranchWarn) // The date of final warning for a bot created branch
    def warnBranchDate = currentDate.minus(daysForBranchWarn) // The date that we warn for a branch becoming stale
    
    print("[Info] The current date is: ${currentDate}")
    print("[Info] A PR is in warning if the date it was last updated on is: ${warnPrDate}")
    print("[Info] A PR is stale if the date it was last updated on was before: ${stalePrDate}")
    print("[Info] A branch is stale if the date it was last updated on is: ${warnBranchDate}")
    print("[Info] A bot branch will be deleted if the date it was last updated on was before: ${deleteBotBranchDate}")

    parallel([
    'PR Cleanup': {
    stage('PR Cleanup') {
        node('aws&&docker') {
        try {
            // Check all open PR's to see if they are stale
            // API URL: GET /api/v3/repos/:org/:repo/pulls?state=opened
            def openPRs = spgithub.getAllOpenPullRequests(repository, org)
            openPRs.each {
                // For determining when a PR's content is considered stale, we have three dates
                // as points of reference:
                //      - created_at: The date the PR was created
                //      - pushed_at: The date of the last commit made
                //      - updated_at: The date of the last update to the PR. This includes messages in the conversation
                // We will use updated_at as we consider a PR as recent if there is any activity
                // on it. It also allows devs to just add a comment if they wish to keep the PR
                // active when the cleanup picks it up as stale.
                def formatPrDate = it.updated_at.replace('T', ' ').replace('Z', '')
                def prDate = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").parse(formatPrDate)
                boolean prWarn = sameDayFmt.format(warnPrDate).equals(sameDayFmt.format(prDate)) // Check if today is the day to send out a warning
                def prNum = it.number.toString()
                def state = ""
                def slackMsg = ""
                if (prDate < stalePrDate) {
                    state = "close"
                    slackMsg = "<${it.html_url}|${it.title}> is over ${daysForPrClose} days old and has been *closed*\n"
                    // API URL: POST /api/v3/repos/:org/:repo/issues/:number/comments
                    // Payload sent: [body: 'MESSAGE']
                    def comment = "This PR was closed by the GitClean bot due to inactivity. See the [GitClean README](https://github.com/KyleLavorato/gitclean-jenkins/blob/master/README.md) for more details."
                    spgithub.postCommentToNamedPR(comment, prNum, repository, org)
                    // API URL: PATCH /api/v3/repos/:org/:repo/pulls/:number
                    // Payload sent: [state: 'closed']
                    spgithub.patchClosePullRequest(prNum, repository, org)
                } else if (prWarn) {
                    state = "warn"
                    slackMsg = "<${it.html_url}|${it.title}> is over ${daysForPrWarn} days old and will be closed on `${prDate.plus(daysForPrClose)}` if not updated\n"
                }
                if (state != "") {
                    // API URL: GET /api/v3/repos/:org/:repo/commits/:commit_sha
                    def author = spgithub.getCommitAuthor(it.merge_commit_sha.toString(), repository, org)
                    author = slackEmailAlias.containsKey(author) ? slackEmailAlias[author] : author
                    def slackUser = slackUserIdFromEmail([email: author, botUser: true, tokenCredentialId: 'jenkinsbot-token'])
                    if (botUsers.contains(author) || slackUser == null) {
                        // This branch was made by a bot so send the slack to the team channel
                        slackUser = squadChannel
                        prLogs += """#${slackUser}-PR: [state:"${state}", title:"${it.title}", url:"${it.html_url}", dateCreated:"${formatPrDate}", age:"${currentDate - prDate}", dateToClose: "${prDate.plus(daysForPrClose)}", author:"${author}"]\n"""
                    } else {
                        // This PR was made by a dev so send a slack message
                        prLogs += """@${slackUser}-PR: [state:"${state}", title:"${it.title}", url:"${it.html_url}", dateCreated:"${formatPrDate}", age:"${currentDate - prDate}", dateToClose: "${prDate.plus(daysForPrClose)}", author:"${author}"]\n"""
                    }
                    addToReport(slackPrReport, slackUser, slackMsg)
                }
            }
        } finally {
			deleteDir()
		}}
    }},

    'Branch Cleanup': {
    stage('Branch Cleanup') {
        node('aws&&docker') {
        try {
        try {
            def allBranchesJson = []
            def pageNumber = 1
            while (true) {
                // API URL: GET /api/v3/repos/:org/:repo/branches?per_page=100&page={pageNumber}
                def branchQuery = spgithub.getAllBranches(pageNumber.toString(), repository, org)
                if (branchQuery.isEmpty()) break
                allBranchesJson.add(branchQuery)
                pageNumber += 1
            }
            // `allBranches` is saved as X pages of 100 branch 
            // entries so it will need to be double iterated
            def allBranches = [:]
            allBranchesJson.each { branchPage ->
                branchPage.each {
                    // We do not want to warn for release branches
                    if (it.protected.toString() == "false") {
                        allBranches.put(it.name, it.commit.sha)
                    }
                }
            }
            print("[Info] Discovered ${allBranches.size()} branches")
            // Date information is not included in the branch, need to 
            // check the last commit in the branch for the date
            allBranches.each { branch, sha ->
                // API URL: GET /api/v3/repos/:org/:repo/commits/:commit
                def commit = spgithub.getCommit(sha, repository, org)
                // By checking the committer instead of author, we get the most recent person
                // to modify the branch. This lets us know if it has been edited since a bot
                // originally made a branch.
                def author = commit.commit.committer.email
                author = slackEmailAlias.containsKey(author) ? slackEmailAlias[author] : author
                def formatBranchDate = commit.commit.committer.date.replace('T', ' ').replace('Z', '')
                def branchDate = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").parse(formatBranchDate)
                def botBranch = (author == commitBotUser)
                def patterns  = [ 'master', 'integration', 'release', 'main', 'c1wsintegration' ]
                patterns.each {
                    if (branch == it || branch.startsWith(it)) {
                        // Ensure we do not try to delete a release branch
                        botBranch = false
                    }
                }
                def html_url = "https://spgithub.trendmicro.com/deep-security/${repository}/tree/${branch}"
                def slackMsg = ""
                def slackUser = slackUserIdFromEmail([email: author, botUser: true, tokenCredentialId: 'jenkinsbot-token'])
                if (botUsers.contains(author) || slackUser == null) {
                    // This branch was made by a bot so send the slack to the team channel
                    slackUser = squadChannel
                }
                if (botBranch) {
                    if (daysForBotBranchDelete != 0 && branchDate < deleteBotBranchDate) {
                        slackMsg = "`${branch}` is over ${daysForBotBranchDelete} days old and has been deleted\n"
                        branchLogs += """#${slackUser}-Branch: [state:"delete", title:"${branch}", dateCreated:"${formatBranchDate}", age:"${currentDate - branchDate}", author:"${author}"]\n"""
                        // API URL: DELETE /api/v3/repos/:org/:repo/git/refs/heads/:branch
                        spgithub.deleteBranch(branch, repository, org)
                    } else if (daysForBotBranchDelete != 0 && sameDayFmt.format(warnBotBranchDateFinal).equals(sameDayFmt.format(branchDate)) || sameDayFmt.format(warnBotBranchDate).equals(sameDayFmt.format(branchDate))) {
                        slackMsg = "`${branch}` is ${currentDate - branchDate} days old and will be deleted on `${branchDate.plus(daysForBotBranchDelete)}` if not updated\n"
                        branchLogs += """#${slackUser}-Branch: [state:"warn", title:"${branch}", dateCreated:"${formatBranchDate}", age:"${currentDate - branchDate}", dateToDelete: "${branchDate.plus(daysForBotBranchDelete)}", author:"${author}"]\n"""
                    }
                } else if (branchDate < warnBranchDate && sendBranchSlack) {
                    slackMsg = "<${html_url}|${branch}> is ${currentDate - branchDate} days old\n"
                    branchLogs += """@${slackUser}-Branch: [state:"warn", title:"${branch}", url:"${html_url}", dateCreated:"${formatBranchDate}", age:"${currentDate - branchDate}", author:"${author}"]\n"""
                }
                if (slackMsg != "") {
                    addToReport(slackBranchReport, slackUser, slackMsg)
                }
            }
        } catch (e) {
            unstable("An exception has occurred in the Branch Cleanup stage")
        }
        } finally {
			deleteDir()
		}}
    }},
    ])
}

stage('Write Logs') {
    node('aws&&docker') {
    try {
        prLogs = "==== PR LOGS ====\n" + prLogs + "\n\n"
        branchLogs = "==== BRANCH LOGS ====\n" + branchLogs
        def gitLog = "gitlog-clean-report-${repository}.txt"
        writeFile(file: gitLog, text: prLogs + branchLogs)
        archiveArtifacts(gitLog)
    } finally {
		deleteDir()
	}}
}

stage('Send Slack Reports') {
    node('aws&&docker') {
    try {
        def userList = []
        slackBranchReport.eachWithIndex { slackUsr, msgs, index ->
            if (!userList.contains(slackUsr)) {
                userList.add(slackUsr)
            }
        }
        slackPrReport.eachWithIndex { slackUsr, msgs, index ->
            if (!userList.contains(slackUsr)) {
                userList.add(slackUsr)
            }
        }
        userList.each { slackUsr ->
            boolean prMsgContent = false
            boolean branchMsgContent = false
            def prMessageForSlack = "*Pull Requests owned by you that require attention:*\n"
            def branchMessageForSlack = "*Branches created by you that have become stale (push a commit to a branch to keep it active or delete it):*\n"
            if(slackPrReport.containsKey(slackUsr)) {
                prMsgContent = true
                slackPrReport[slackUsr].each {
                    prMessageForSlack += "• " + it
                }
            }
            if(slackBranchReport.containsKey(slackUsr)) {
                branchMsgContent = true
                slackBranchReport[slackUsr].each {
                    branchMessageForSlack += "• " + it
                }
            }
            // Check if the text we want to send is too long. The `text` field of the
            // `section` block has a max size of 3000 characters. The PR block should
            // never be over the character limit, only the branch one may exceed the
            // limit. In the rare case that the PR block is, just truncate it so the
            // sendSlack does not fail.
            if (prMessageForSlack.length() > 2950) {
                prMessageForSlack.substring(0, 2625)
                prMessageForSlack += "...\n"
                prMessageForSlack += " *NOTE:* The full PR cleanup report is too long to send over Slack. Please filter the full logs by `${slackUsr}` to see all your notifications, available here: ${env.BUILD_URL}"
            }
            prBlock =
            [
                "type": "section",
                "text": [
                    "type": "mrkdwn",
                    "text": "${prMessageForSlack}"
                ]
            ]
            branchBlocks = []
            def i = 0
            def splitBranchMessages = splitLongMessage(branchMessageForSlack, 2950)
            splitBranchMessages.each {
                // Check if even after splitting we are over the 4 message limit.
                // If so, truncate the final message and provide a link to the
                // full logs.
                // All blocks after the 4th will be ignored when sending the slack
                // message. Only the first 4 are sent always.
                if (i == 3 && it.length() > 2625 && splitBranchMessages.size() > 4) {
                    it = it.substring(0, 2625)
                    it += "...\n"
                    it += "   *NOTE:* The full branch cleanup report is too long to send over Slack. Please filter the full logs by `${slackUsr}` to see all your notifications, available here: ${env.BUILD_URL}"
                }
                def entry = 
                [
                    "type": "section",
                    "text": [
                        "type": "mrkdwn",
                        "text": "${it}"
                    ]
                ]
                branchBlocks.add(entry)
                i += 1
            }
            nullBlock = 
            [
                "type": "section",
                "text": [
                    "type": "mrkdwn",
                    "text": "\n"
                ]
            ]
            blocks = [
                [
                    "type": "header",
                    "text": [
                        "type": "plain_text",
                        "text": "Git Cleanup Report for ${repository}"
                    ]
                ],
                [
                    "type": "divider"
                ],
                prMsgContent ? prBlock : nullBlock,
                [
                    "type": "divider"
                ],
                (branchBlocks[0] != null && branchMsgContent) ? branchBlocks[0] : nullBlock,
                (branchBlocks[1] != null && branchMsgContent) ? branchBlocks[1] : nullBlock,
                (branchBlocks[2] != null && branchMsgContent) ? branchBlocks[2] : nullBlock,
                (branchBlocks[3] != null && branchMsgContent) ? branchBlocks[3] : nullBlock,
                [
                    "type": "divider"
                ],
                [
                    "type": "section",
                    "text": [
                        "type": "mrkdwn",
                        "text": "This bot was created by _Squad Prime_. If you believe you have received this message in error or have feedback on the bot's operation, please contact us at #squad-prime. If you wish to change the configuration of the bot for your repository, the settings are located <https://github.com/KyleLavorato/gitclean-jenkins/blob/master/gitclean.json|here>. See the <https://github.com/KyleLavorato/gitclean-jenkins/blob/master/README.md|GitClean README> for more details."
                    ]
                ],
            ]
            sendSlackMessage(slackUsr, blocks)
        }
    } finally {
		deleteDir()
	}}
}

}
