import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.TextEditorLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LightweightCargoProject(private val myFixture: CodeInsightTestFixture) {
    private fun validateSource(source: String): Result<Unit> {
        val caretIndex = source.indexOf("<caret>")
        if (caretIndex == -1) {
            return Result.failure(Exception("Caret not found in test case source"))
        }
        return Result.success(Unit)
    }

    fun addFile(name: String, source: String): Result<Unit> {
        validateSource(source)
        val file = myFixture.addFileToProject("$name.rs", source)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return Result.success(Unit)
    }

    suspend fun collectUsages(): Result<Map<Position, Usage>> {
        val usages = withContext(Dispatchers.EDT) {
            val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: error("No element at caret")
            val namedElement = element.parent
            collectUnitTestUsages(namedElement)
        }
        return Result.success(usages)
    }

    private fun collectUnitTestUsages(element: PsiElement): Map<Position, Usage> =
        ReferencesSearch.search(element).findAll().associate { reference ->
            val usage = UsageInfo2UsageAdapter(UsageInfo(reference))
            val position = (usage.location as TextEditorLocation).position
            Position(position.line + 1, position.column + 1) to usage
        }
}