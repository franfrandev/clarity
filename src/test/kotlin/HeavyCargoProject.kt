import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.replaceService
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.lang.core.psi.ext.RsTraitOrImpl
import kotlin.io.path.Path

class HeavyCargoProject(
    private val project: Project,
    private val myFixture: CodeInsightTestFixture,
    private val testDataPath: String,
    private val testRootDisposable: Disposable
) {
    suspend fun setupProject(name: String): Result<Unit> {
        val sourceRoot = Path(testDataPath, "projects", name, "project")
        myFixture.tempDirFixture.copyAll(
            sourceRoot.toString(), ""
        ) { file -> file.name != "target" && file.name != "Cargo.lock" }

        val root = myFixture.findFileInTempDir("")

        val testProjectService = TestCargoProjectsServiceImpl(myFixture.project, MainScope())
        val ws = testProjectService.createTestCargoWorkspace(root)
        testProjectService.createTestProject(root, ws)

        project.replaceService(CargoProjectsService::class.java, testProjectService, testRootDisposable)

        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return Result.success(Unit)
    }

    suspend fun collectProjectUsages(testCase: ProjectTestCases): Result<Map<String, Map<Position, Usage>>> =
        runCatching {
            val caretFilePath = testCase.caret.path
            log.debug("Caret file path: $caretFilePath")
            val caretFile = myFixture.findFileInTempDir(caretFilePath) ?: error("Caret file not found: $caretFilePath")

            val caretPos = testCase.caret.position
            val logicalPosition = LogicalPosition(caretPos.line - 1, caretPos.column - 1)
            withContext(Dispatchers.EDT) {
                myFixture.configureFromExistingVirtualFile(caretFile)
                check(myFixture.file.virtualFile == caretFile) {
                    "Fixture configured ${myFixture.file.virtualFile?.path}, expected ${caretFile.path}"
                }
                myFixture.editor.caretModel.moveToLogicalPosition(logicalPosition)

                val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: error("No element at caret")

                val namedElement =
                    PsiTreeUtil.getParentOfType(element, RsTraitOrImpl::class.java) ?: PsiTreeUtil.getParentOfType(
                        element, RsFunction::class.java
                    ) ?: PsiTreeUtil.getParentOfType(
                        element, RsNamedElement::class.java
                    ) ?: element
                check(namedElement.containingFile.virtualFile == caretFile) {
                    "Searching references from ${namedElement.containingFile.virtualFile?.path}, expected ${caretFile.path}"
                }
                collectAllProjectUsages(namedElement)
            }
        }

    private fun collectAllProjectUsages(element: PsiElement): Map<String, Map<Position, Usage>> {
        val map = mutableMapOf<String, MutableMap<Position, Usage>>()
        val fixtureRoot = myFixture.tempDirFixture.getFile(".") ?: error("Fixture project root is not available")
        val fixtureRootPath = fixtureRoot.path.trimEnd('/') + "/"
        val refs = ReferencesSearch.search(element).findAll()
        for (ref in refs) {
            log.debug("Processing reference: ${ref.element.text}")
            val usage = UsageInfo2UsageAdapter(UsageInfo(ref))
            log.debug("Processing usage: ${usage.plainText}")
            val file = ref.element.containingFile
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
                ?: error("No document for usage ${usage.plainText}")
            val offset = ref.element.textRange.startOffset + ref.rangeInElement.startOffset
            val line = document.getLineNumber(offset)
            val position = Position(
                line + 1, offset - document.getLineStartOffset(line) + 1
            )
            val virtualFile = ref.element.containingFile.virtualFile ?: error("No file for usage")
            val virtualPath = virtualFile.path.removePrefix(fixtureRootPath)
            map.getOrPut(virtualPath) { mutableMapOf() }[position] = usage
        }
        return map
    }
}