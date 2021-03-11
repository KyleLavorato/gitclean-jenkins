# GitClean Bot

This folder contains a Jenkins job defined as code that acts as a maintenance bot for git branches and GitHub Pull Requests. It is designed to run as a set of two Jenkins jobs:
1. `git-cleanup.groovy` - a base job that conducts the maintenance tasks on a particular target repository
2. `git-cleanup-scheduler.groovy` - a scheduled job that runs daily to trigger the base job for each target
3. `SupportingFunctions.groovy` - a set of functions to be added to a Jenkins shared library

### Bot Behaviors

The bot conducts the following maintenance tasks during each run:
* Warn developers when their Pull Requests have been left open without any activity
* Close Pull Requests that have not had any activity after the warning period
* Delete branches that are made by a commit bot and never updated by a human developer
* Warn developers when their branches have been left without any activity
(developers can delete the branches or push changes to the branches to make them active)

### Bot Configuration

All of the maintenance tasks described above are fully configurable per repository. This allows repository owners to control how strict they want their cleanup to be and how often they want their developers to be notified. Bot configuration for each repository is controlled by an entry in the `gitclean.json` file. For example, the default suggested settings are shown in the code block below:
```
"hello-world-repo":
{
    "repository": "hello-world-repo",
    "org": "sample-org",
    "squadChannel": "squad-example",
    "daysForPrWarn": "7",
    "daysForPrClose": "14",
    "daysForBotBranchDelete": "14",
    "daysForBranchWarn": "90",
    "branchWarnLevel": "@biweekly"
},
```
The configuration settings are described below:
* `repository`: name of the target repository
* `org`: GitHub organization that manages the repository (usually `deep-security` or `dsdevops`)
* `squadChannel`: Slack channel used for notifications when no developer has been identified
* `daysForPrWarn`: the number of days a PR will sit without updates before a warning is sent
* `daysForPrClose`: the number of days a PR will sit without updates before it is automatically closed
* `daysForBotBranchDelete`: for a branch created by a commit bot, the number of days a branch will sit without human updates before it is automatically deleted. Set this value `0` to *disable* this functionality. Warnings will be sent to the team's Slack channel after half the time has passed and once again the day before deletion.
* `daysForBranchWarn`: for a branch created by a developer, the number of days a branch will sit without human updates before the developer is asked to clean up the stale branch
* `branchWarnLevel`: one of several pre-defined values that will control how often the cleanup branch messages are sent to developers (to reduce spam):
    * `@daily`: send the message on every daily run of the bot
    * `@weekly`: send the message every Wednesday
    * `@biweekly`: send the message on the 1st and 14th of every month
    * `@monthly`: send the message on the 1st of every month

### Requirements and Setup

There are some requirements that must be met before this bot can be applied to a repository.

#### User Level Setup

Notifications from the job are sent by the `Jenkinsbot` Slack app. This bot is able to message any user directly, but before the bot is able to send a message to a team channel, it must be added to that channel. Follow the instructions below to add the Jankinsbot to your team channel:
1. In your Slack channel, Show channel details (&#9432;)
2. Under the `More` (...) option, select `Add apps`
3. Search for `Jenkinsbot` and click the `Add` button

#### Jenkins Level Setup

To setup this job from scratch or to deploy to a new shard, there are some setup steps required. The required job must be setup, required plugins installed, and credentials must be added for the bot in Jenkins.

The required plugins for the bot to function are:

* [httpRequest Plugin](https://www.jenkins.io/doc/pipeline/steps/http_request/)
* [Slack Notification Plugin](https://plugins.jenkins.io/slack/)

The `SupportingFunctions.groovy` file should be added to a shared library as the name `spgithub.groovy`. This is required to resolve the function calls in the `git-cleanup.groovy` file.

As mentioned, the bot is defined by a set of two groovy files which each create an independent job. The `git-cleanup-scheduler.groovy` must be setup as a Cron job that will execute daily. The other file `git-cleanup.groovy` is the base job that will perform the bot actions given a configuration. The scheduler will read in the config file `gitclean.json` and trigger the base job with the parameters defined in the config file for each repository. As a result, the scheduler must have the proper job name for the base job in the Jenkins shard that it is running on.

In addition to setting up the two jobs in Jenkins, the credential for the Jenkinsbot must also be added to the shard in which the jobs are running. The `secret text` credential must be added with the access token named `jenkinsbot-token`. If a new Slack app must be set up for any reason, the instructions to do so are located in the [Slack Notification Plugin Documentation](https://plugins.jenkins.io/slack/).

### Examples

Examples of the messages that the bot sends out can be found in the `examples` folder. There are two examples provided as the bot has two message types:

1. Team Channel Message. This is a message sent to a specific slack channel of the team that is responsible for the repository. This will contain information on PRs and branches requiring cleaning/notice, that are not owned by a specific user (made by a bot).
2. User Personal Message. This is a message sent as a DM to a user. It contains information on all the PRs and branches requiring cleaning/notice, that the user has personally created/updated.