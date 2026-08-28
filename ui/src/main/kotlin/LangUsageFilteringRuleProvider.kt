package com.github.clarity.ui

import com.github.clarity.go.GoTestsUsageFilteringRule
import com.github.clarity.rust.RustTestsUsageFilteringRule
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.extensions.PluginId
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.rules.PsiElementUsage
import com.intellij.usages.rules.UsageFilteringRule
import com.intellij.usages.rules.UsageFilteringRuleProvider

val log = Logger.getInstance("com.github.clarity.ui")

class LangUsageFilteringRuleProvider : UsageFilteringRuleProvider {
    override fun getApplicableRules(project: Project): Collection<UsageFilteringRule> = emptyList()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getActiveRules(project: Project): Array<UsageFilteringRule> = if (isEnabled(project)) {
        buildList {
            if (isPluginInstalled(RUST_PLUGIN_ID)) add(RustTestsUsageFilteringRule())
            if (isPluginInstalled(GO_PLUGIN_ID)) add(GoTestsUsageFilteringRule())
        }.toTypedArray()
    } else {
        UsageFilteringRule.EMPTY_ARRAY
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun createFilteringActions(view: UsageView): Array<AnAction> = arrayOf(TestsFilterToggleAction(view))

    private class TestsFilterToggleAction(
        private val view: UsageView,
    ) : ToggleAction(
        "Exclude Usages in Tests",
        "Hide usages inside Rust and Go tests",
        AllIcons.Actions.Close,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = e.project?.let(::isEnabled) ?: false

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            val project = e.project ?: return
            PropertiesComponent.getInstance(project).setValue(ENABLED_KEY, state, false)
            project.messageBus.syncPublisher(UsageFilteringRuleProvider.RULES_CHANGED).run()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            val visible = isSupportedUsageView(e)
            e.presentation.isVisible = visible
            e.presentation.isEnabled = visible
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        private fun isSupportedUsageView(event: AnActionEvent): Boolean {
            val targets = event.getData(UsageView.USAGE_TARGETS_KEY)
            return targets != null && targets.any { target ->
                log.warn("Checking if target is supported: $target")
                val element = (target as? PsiElementUsageTarget)?.element ?: return@any false
                element.language.id in SUPPORTED_LANGUAGE_IDS
            } || view.usages.any { usage ->
                log.warn("Checking if usage is supported: $usage")
                val element = (usage as? PsiElementUsage)?.element ?: return@any false
                element.language.id in SUPPORTED_LANGUAGE_IDS
            }
        }

        private fun isEnabled(project: Project): Boolean =
            PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, false)
    }

    companion object {
        private const val ENABLED_KEY = "com.github.clarity.testsFilter.enabled"
        private val SUPPORTED_LANGUAGE_IDS = setOf("Rust", "go")
        private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")
        private val GO_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.go")

        private fun isPluginInstalled(pluginId: PluginId): Boolean = PluginManagerCore.isPluginInstalled(pluginId)

        private fun isEnabled(project: Project): Boolean =
            PropertiesComponent.getInstance(project).getBoolean(ENABLED_KEY, false)
    }
}
