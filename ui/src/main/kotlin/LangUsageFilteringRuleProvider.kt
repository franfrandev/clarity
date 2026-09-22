package com.github.clarity.ui

import com.github.clarity.go.GoTestsUsageFilteringRule
import com.github.clarity.rust.RustTestsUsageFilteringRule
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.extensions.PluginId
import com.intellij.usages.Usage
import com.intellij.usages.rules.UsageFilteringRule
import com.intellij.usages.rules.UsageFilteringRuleProvider

private val RUST_PLUGIN_ID = PluginId.getId("com.jetbrains.rust")
private val GO_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.go")

class LangUsageFilteringRuleProvider : UsageFilteringRuleProvider {
    override fun getApplicableRules(project: Project): Collection<UsageFilteringRule> = buildList {
        val rules = buildList {
            if (isPluginInstalled(RUST_PLUGIN_ID)) add(RustTestsUsageFilteringRule())
            if (isPluginInstalled(GO_PLUGIN_ID)) add(GoTestsUsageFilteringRule())
        }
        if (rules.isNotEmpty()) add(TestsUsageFilteringRule(rules))
    }

    private fun isPluginInstalled(pluginId: PluginId): Boolean = PluginManagerCore.isPluginInstalled(pluginId)

    private class TestsUsageFilteringRule(
        private val rules: List<UsageFilteringRule>,
    ) : UsageFilteringRule {
        override fun getRuleId(): String = RULE_ID

        override fun getActionId(): String = ACTION_ID

        override fun isVisible(usage: Usage): Boolean = rules.all { it.isVisible(usage) }

        companion object {
            private const val RULE_ID = "com.github.clarity.testsFilter"
            private const val ACTION_ID = "Clarity.ExcludeUsagesInTests"
        }
    }
}
