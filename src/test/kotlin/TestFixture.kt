import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.github.filteroutrusttests.RustTestsUsageFilteringRule
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.TextEditorLocation
import com.intellij.openapi.vfs.readText
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.lang.core.psi.ext.RsTraitOrImpl
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

abstract class TestInterface : TestFixture() {
    fun runUnitTest(name: String) {
        val testCase = readUnitTestCase(name)

        runBlocking {
            runUnitTestCase(name, testCase).onFailure { exception ->
                fail("Test case '$name' failed: ${exception.message}")
            }
        }
    }

    fun runIntegrationTest(name: String) {
        val testCase = readIntegrationTestCase(name)

        runProjectsTestCase(name, testCase).onFailure { exception ->
            log.error(
                "Test case '$name' failed: ${exception.message}, cause: ${exception.cause}, stack: ${exception.stackTraceToString()}",
                exception
            )
            fail("Test case '$name' failed: ${exception.message}")
        }
    }
}

data class Position(
    var line: Int = 0, var column: Int = 0
)

data class Expected(
    var position: Position = Position(), var visible: Boolean = false
)

data class TestCase(
    var source: String = "", var expected: List<Expected> = emptyList()
)

data class Caret(
    var path: String = "", var position: Position = Position()
)

data class TestFile(
    var path: String = "", var expected: List<Expected> = arrayListOf()
)

data class ProjectTestCases(
    var caret: Caret = Caret(), var files: List<TestFile> = arrayListOf()
)

abstract class TestFixture : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean {
        return false
    }

    override fun getTestDataPath(): String {
        return File("src/test/testData").absolutePath
    }

    override fun setUp() {
        super.setUp()
        val pluginId = PluginId.getId("com.jetbrains.rust")
        val isRustInstalled = PluginManagerCore.isPluginInstalled(pluginId)
        assertTrue("Rust plugin is not installed", isRustInstalled)
        val plugin = PluginManagerCore.getPlugin(pluginId)
        assertNotNull("Rust plugin is not found", plugin)
    }

    fun readUnitTestCase(name: String): TestCase {
        val filename = "$name.toml"
        val testCasePath = Path(testDataPath, "unit", filename)
        if (!(testCasePath.exists())) {
            error("Unit test case '$filename' not found")
        }
        val mapper = TomlMapper()
        val testCase = mapper.readValue(testCasePath.toFile(), TestCase::class.java)
        return testCase
    }

    fun readIntegrationTestCase(name: String): ProjectTestCases {
        val testCasePath = Path(testDataPath, "projects", name)
        if (!(testCasePath.exists())) {
            error("Integration test case '$name' not found")
        }
        if (!(testCasePath.isDirectory())) {
            error("Integration test case '$name' is not a directory")
        }
        val testFile = testCasePath.resolve("expected.toml")
        val mapper = TomlMapper()
        val testCase = mapper.readValue(testFile.toFile(), ProjectTestCases::class.java)
        return testCase
    }

    private fun collectUsages(element: PsiElement): Map<Position, Usage> =
        ReferencesSearch.search(element).findAll().associate { reference ->
            val usage = UsageInfo2UsageAdapter(UsageInfo(reference))
            val position = (usage.location as TextEditorLocation).position
            Position(position.line + 1, position.column + 1) to usage
        }

    private fun collectProjectUsages(element: PsiElement): Map<String, Map<Position, Usage>> {
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

    private fun checkExpectedUsages(
        rule: RustTestsUsageFilteringRule, usages: Map<Position, Usage>, expected: List<Expected>
    ): Result<Unit> = runCatching {
        require(expected.isNotEmpty()) { "No expectations provided." }
        require(expected.size == usages.size) {
            "Usage count mismatch: found ${usages.size}, expected ${expected.size}. Found usages at: ${usages.keys}"
        }

        expected.forEach { expected ->
            val usage = usages[expected.position]
                ?: error("Usage at ${expected.position.line}:${expected.position.column} expected but not found. Found usages: ${usages.keys}")

            check(expected.visible == rule.isVisible(usage)) {
                "Visibility mismatch at ${expected.position.line}:${expected.position.column}: expected visible=${expected.visible}"
            }
        }
    }

    suspend fun runUnitTestCase(name: String, testCase: TestCase): Result<Unit> {
        val caretString = "<caret>"
        val caretIndex = testCase.source.indexOf(caretString)
        if (caretIndex == -1) {
            error("Caret not found in test case source")
        }
        val file = myFixture.addFileToProject("$name.rs", testCase.source.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val expectedUsages = withContext(Dispatchers.EDT) {
            val element = myFixture.file.findElementAt(myFixture.caretOffset) ?: error("No element at caret")
            val namedElement = element.parent
            val usages = collectUsages(namedElement)
            val rule = RustTestsUsageFilteringRule()
            checkExpectedUsages(rule, usages, testCase.expected)
        }
        return expectedUsages
    }

    fun runProjectsTestCase(name: String, testCase: ProjectTestCases): Result<Unit> = runCatching {
        val projectRoot = "projects/$name/project"
        val testPath = Path(testDataPath, projectRoot)
        val testsExists = File(testPath.toString(), "tests").isDirectory

        myFixture.copyFileToProject("$projectRoot/Cargo.toml", "Cargo.toml")
        myFixture.copyDirectoryToProject("$projectRoot/src", "src")
        if (testsExists) {
            myFixture.copyDirectoryToProject("$projectRoot/tests", "tests")
        }
        PsiTestUtil.addContentRoot(module, myFixture.tempDirFixture.getFile(".")!!)
        if (testsExists) {
            val testDir = myFixture.findFileInTempDir("tests")!!
            PsiTestUtil.addSourceRoot(module, testDir)
        }

        val root = myFixture.findFileInTempDir("")

        val testProjectService = TestCargoProjectsServiceImpl(myFixture.project, MainScope())
        val ws = testProjectService.createTestCargoWorkspace(root)

        val projectUsages = runBlocking {
            testProjectService.createTestProject(root, ws)

            project.replaceService(CargoProjectsService::class.java, testProjectService, testRootDisposable)

            IndexingTestUtil.waitUntilIndexesAreReady(project)

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
                collectProjectUsages(namedElement)
            }
        }

        log.debug("Found ${projectUsages.size} usages in project:")
        for ((path, usages) in projectUsages) {
            log.debug("Usages for $path:")
            for (usage in usages) {
                log.debug("  - $usage")
            }
        }
        val rule = RustTestsUsageFilteringRule()

        testCase.files.forEach { case ->
            val testPath = case.path
            val testFile =
                myFixture.findFileInTempDir(testPath) ?: error("Test file not found in fixture temp dir: $testPath")
            val source = testFile.readText()
            val testCase = TestCase(source, case.expected)
            val usages = projectUsages[testPath] ?: error("Usages not found for test file: $testPath")

            val result = checkExpectedUsages(rule, usages, testCase.expected)
            if (result.isFailure) {
                val foundPos = usages.keys
                throw RuntimeException("Test case '${testPath}' failed: ${result.exceptionOrNull()?.message}. Found usages at: $foundPos")
            }
        }

        Result.success(Unit)
    }
}
