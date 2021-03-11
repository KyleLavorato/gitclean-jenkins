////////////
// Requests
////////////

def GET_RAW(String method, String url, boolean null404) {
    def response
    retryWithBackoff([
        retryCount: 6,
        backoffTime: 20,
        resetCount: 3
    ]) {
        response = httpRequest([
            authentication: token(),
            url: urlAPI()+url,
            validResponseCodes: '100:600',
        ])
        def code = response.getStatus()
        if (404 == code && null404) {
            response = null
            return
        }
        if (400 <= code) {
            def side = 'client'
            if (500 <= code) {
                side = 'server'
            }
            def errorArg = [
                type: "dsgithub.${side}.get${method}",
                message: """
                dsgithub.get${method}: HTTP ${response}
                Response: ${response.content}\nRequest: GET ${url}
                """.stripIndent().trim(),
            ]
            // 5XX are internal server errors, we'll assume we can sleep and retry.
            // 429 is "Too Many Requests" which is meant to be a retry.
            if (code >= 500 || code == 429) {
                error(errorArg)
            } else {
                // Other 4XX errors indicate a bad request that should not be retried.
                retryWithBackoff.errorNoRetry(errorArg)
            }
        }
    }
    return response
}

def GET(String method, String url, boolean null404) {
    def response = GET_RAW(method, url, null404)
    if (response == null) {
        return null
    }
    def json = new JsonSlurperClassic().parseText(response.content)
    return json
}

def GET(String method, String url) {
    return GET(method, url, false)
}

def POST(String method, String url, Map map) {
    def body = JsonOutput.toJson(map)
    def response
    retryWithBackoff([
        retryCount: 6,
        backoffTime: 20,
        resetCount: 3
    ]) {
        response = httpRequest([
            acceptType: 'APPLICATION_JSON',
            contentType: 'APPLICATION_JSON',
            authentication: token(),
            httpMode: 'POST',
            url: urlAPI()+url,
            requestBody: body,
            validResponseCodes: '100:600',
        ])
        def code = response.getStatus()
        if (400 <= code) {
            def side = 'client'
            if (500 <= code) {
                side = 'server'
            }
            def errorArg = [
                type: "dsgithub.${side}.post${method}",
                message: """
                dsgithub.post${method}: HTTP ${response}
                Response: ${response.content}
                Request: POST ${url}: [${body}]
                """.stripIndent().trim(),
            ]
            // 5XX are internal server errors, we'll assume we can sleep and retry.
            // 429 is "Too Many Requests" which is meant to be a retry.
            if (code >= 500 || code == 429) {
                error(errorArg)
            } else {
                // Other 4XX errors indicate a bad request that should not be retried.
                retryWithBackoff.errorNoRetry(errorArg)
            }
        }
    }
    def json = new JsonSlurperClassic().parseText(response.content)
    return json
}

def PATCH(String url, Map map) {
    def body = JsonOutput.toJson(map)
    def response = httpRequest([
        acceptType: 'APPLICATION_JSON',
        contentType: 'APPLICATION_JSON',
        authentication: token(),
        httpMode: 'PATCH',
        url: urlAPI()+url,
        requestBody: body,
        validResponseCodes: '100:600',
    ])
    def code = response.getStatus()
    def json = new JsonSlurperClassic().parseText(response.content)
    return json
}

def DELETE(String url) {
    def response = httpRequest([
        authentication: token(),
        httpMode: 'DELETE',
        url: urlAPI()+url,
        validResponseCodes: '100:600',
    ])
    def code = response.getStatus()
    return code
}

////////////
// Functions
////////////

def getAllOpenPullRequests(String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/pulls?state=opened"
    return GET('PullRequest', url)
}

def postCommentToNamedPR(String comment, String number, String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/issues/${checkNumber(number)}/comments"
    return POST('AddComment', url, [body: comment])
}

def patchClosePullRequest(String number, String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/pulls/${checkNumber(number)}"
    return PATCH(url, [state: 'closed'])
}

def getCommitAuthor(String commit_sha, String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/commits/${commit_sha}"
    def json = GET('Users', url)
    if (json == null || json == "")  {
        return null
    }
    return json.commit.author.email
}

def getAllBranches(String pageNumber, String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/branches?per_page=100&page=${checkNumber(pageNumber)}"
    return GET('Branch', url)
}

def getCommit(String commit, String repo, String org) {
    def url = "repos/${check(org)}/${check(repo)}/commits/${checkBranch(commit)}"
    return GET('Commit', url)
}

def deleteBranch(String branch, String repo, String org) {
    def branchRef = branch
    if (!branchRef.startsWith('heads/')) {
        branchRef = 'heads/' + branchRef
    }
    def url = "repos/${check(org)}/${check(repo)}/git/refs/${branchRef}"
    if (branch.startsWith('master') ||
        branch.startsWith('integration') ||
        branch.startsWith('release')) {
        error "Trying to delete a restricted branch: '${branch}'"
    }
    return DELETE(url)
}