package com.github.clarity.go

import com.goide.psi.GoFunctionDeclaration
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.usages.Usage
import com.intellij.usages.rules.PsiElementUsage
import com.intellij.usages.rules.UsageFilteringRule

val log = Logger.getInstance("com.github.clarity.go")

class GoTestsUsageFilteringRule : UsageFilteringRule {
    override fun getRuleId(): String = RULE_ID

    override fun isVisible(usage: Usage): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        log.warn("Checking visibility of usage: $usage")

        val psiUsage = usage as? PsiElementUsage ?: return@compute true
        val element = psiUsage.element ?: return@compute true

        if (isInTestFile(element)) {
            return@compute false
        }

        if (!isGoElement(element)) {
            return@compute true
        }

        val startTime = System.currentTimeMillis()
        val filterOut = shouldFilterOut(element, startTime)
        return@compute !filterOut
    }

    private fun isGoElement(element: PsiElement): Boolean {
        val languageId = element.containingFile?.language?.id ?: return false
        return languageId == "go"
    }

    private fun isInTestFile(element: PsiElement): Boolean {
        val virtualFile = element.containingFile?.virtualFile ?: return false
        return virtualFile.name.endsWith("_test.go")
    }

    private fun shouldFilterOut(element: PsiElement, startTime: Long): Boolean {
        return isInsideGoTestFunction(element, mutableSetOf(), startTime, 0)
    }

    private fun isInsideGoTestFunction(
        element: PsiElement, visiting: MutableSet<PsiElement>, startTime: Long, depth: Int
    ): Boolean {
        ProgressManager.checkCanceled()

        if (depth > MAX_DEPTH || hasTimedOut(startTime)) {
            return false
        }

        if (visiting.contains(element)) return false

        visiting.add(element)
        try {
            return CachedValuesManager.getCachedValue(element, insideTestKey) {
                CachedValueProvider.Result.create(
                    calculateIsInsideGoTest(element, visiting, startTime, depth),
                    PsiModificationTracker.MODIFICATION_COUNT
                )
            }
        } finally {
            visiting.remove(element)
        }
    }

    private fun referencesOf(element: PsiElement): List<PsiReference> =
        CachedValuesManager.getCachedValue(element, referencesKey) {
            CachedValueProvider.Result.create(
                ReferencesSearch.search(element).findAll().toList(), PsiModificationTracker.MODIFICATION_COUNT
            )
        }

    private fun calculateIsInsideGoTest(
        element: PsiElement, visiting: MutableSet<PsiElement>, startTime: Long, depth: Int
    ): Boolean {
        if (isInTestFile(element)) {
            return true
        }

        if (element is GoFunctionDeclaration) {
            val references = referencesOf(element)

            var hasReferences = false
            val allInTests = references.all {
                if (hasTimedOut(startTime)) {
                    return false // this took too long, return a potential false negative.
                }

                hasReferences = true
                val inTest = isInsideGoTestFunction(it.element, visiting, startTime, depth + 1)
                inTest
            }

            if (hasReferences && allInTests) {
                return true
            }
        }

        val parent = element.parent ?: return false
        return isInsideGoTestFunction(parent, visiting, startTime, depth + 1)
    }

    private fun hasTimedOut(startTime: Long): Boolean {
        return System.currentTimeMillis() - startTime > TIMEOUT_MS
    }

    companion object {
        private val referencesKey = Key.create<CachedValue<List<PsiReference>>>(
            "go.tests.usageFiltering.references"
        )
        private val insideTestKey = Key.create<CachedValue<Boolean>>(
            "go.tests.usageFiltering.insideTest"
        )

        const val RULE_ID: String = "com.github.clarity.notGoTests"
        const val MAX_DEPTH: Int = 20
        const val TIMEOUT_MS: Long = 500
    }
}
