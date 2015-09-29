package org.rundeck.plugin.scm.git.imp.actions

import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.JobImporter
import com.dtolabs.rundeck.plugins.scm.ScmExportResult
import com.dtolabs.rundeck.plugins.scm.ScmExportResultImpl
import com.dtolabs.rundeck.plugins.scm.ScmPluginException
import org.eclipse.jgit.merge.MergeStrategy
import org.rundeck.plugin.scm.git.BaseAction
import org.rundeck.plugin.scm.git.GitImportAction
import org.rundeck.plugin.scm.git.GitImportPlugin

import static org.rundeck.plugin.scm.git.BuilderUtil.inputView
import static org.rundeck.plugin.scm.git.BuilderUtil.property

/**
 * Created by greg on 9/28/15.
 */
class PullAction extends BaseAction implements GitImportAction {
    PullAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    @Override
    BasicInputView getInputView(GitImportPlugin plugin) {

        def status = plugin.getStatusInternal(false)
        def props = [
                property {
                    string "status"
                    title "Git Status"
                    renderingOption StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.STATIC_TEXT
                    renderingOption StringRenderingConstants.STATIC_TEXT_CONTENT_TYPE_KEY, "text/x-markdown"
                    defaultValue status.message + """

Pulling from remote branch: `${plugin.branch}`"""
                },
        ]
        if (status.branchTrackingStatus?.behindCount > 0 && status.branchTrackingStatus?.aheadCount > 0) {
            props.addAll([
                    property {
                        select "refresh"
                        title "Synch Method"
                        description """Choose a method to synch the remote branch changes with local git repository.

* `merge` - merge remote changes into local changes
* `rebase` - rebase local changes on top of remote
"""
                        values "merge", "rebase"
                        defaultValue "merge"
                        required true
                    },
                    property {
                        select "resolution"
                        title "Conflict Resolution Strategy"
                        description """Choose a strategy to resolve conflicts in the synched files.

* `ours` - apply our changes over theirs
* `theirs` - apply their changes over ours"""
                        values(MergeStrategy.get()*.name)
                        defaultValue "ours"
                        required true
                    },
            ]
            )
        }
        inputView(id) {
            title this.title
            description this.description
            if (status.branchTrackingStatus?.behindCount > 0) {
                buttonTitle("Pull Changes")
            } else {
                buttonTitle "Synch"
            }
            properties props
        }
    }

    @Override
    ScmExportResult performAction(
            final GitImportPlugin plugin,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, Object> input
    ) throws ScmPluginException
    {
        def status = plugin.getStatusInternal(false)


        if (status.branchTrackingStatus?.behindCount > 0 && status.branchTrackingStatus?.aheadCount > 0) {
            gitResolve(plugin, input)
        } else if (status.branchTrackingStatus?.behindCount > 0) {
            gitPull(plugin)
        } else {
            //no action
        }

    }

    ScmExportResult gitPull(final GitImportPlugin plugin) {
        def pullResult = plugin.git.pull().setRemote('origin').setRemoteBranchName(plugin.branch).call()

        def result = new ScmExportResultImpl()
        result.success = pullResult.successful
        result.message = pullResult.toString()
        result
    }

    ScmExportResult gitResolve(final GitImportPlugin plugin, final Map<String, Object> input) {


        if (input.refresh == 'rebase') {
            def pullbuilder = plugin.git.pull().setRemote('origin').setRemoteBranchName(plugin.branch)
            pullbuilder.setRebase(true)
            def pullResult = pullbuilder.call()

            def result = new ScmExportResultImpl()
            result.success = pullResult.successful
            result.message = pullResult.toString()
            return result
        } else {
            //fetch, then
            //merge

            def fetchResult = plugin.git.fetch().setRemote('origin').call()
            def update = fetchResult.getTrackingRefUpdate("refs/remotes/origin/${plugin.branch}")


            def strategy = MergeStrategy.get(input.resolution)
            def mergebuild = plugin.git.merge().setStrategy(strategy)
            def commit = plugin.git.repository.resolve("refs/remotes/origin/${plugin.branch}")

            mergebuild.include(commit)

            def mergeresult = mergebuild.call()

            def result = new ScmExportResultImpl()
            result.success = mergeresult.mergeStatus.successful
            result.message = mergeresult.toString()
            return result

        }
    }
}