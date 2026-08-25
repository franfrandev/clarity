import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.github.filteroutrusttests.RustTestsUsageFilteringRule
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.readText
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usages.Usage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

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

abstract class UnitTestFixture : TestFixture() {
    fun runUnitTest(name: String) {
        val testCase = readUnitTestCase(name)

        runBlocking {
            runUnitTestCase(name, testCase).onFailure { exception ->
                fail("Test case '$name' failed: ${exception.message}")
            }
        }
    }

    private suspend fun runUnitTestCase(name: String, testCase: TestCase): Result<Unit> {
        val source = testCase.source.trimIndent()
        val project = LightweightCargoProject(myFixture)
        project.addFile(name, source)
        val rule = RustTestsUsageFilteringRule()
        val usages = project.collectUsages().getOrNull() ?: error("No usages found")
        return checkExpectedUsages(rule, usages, testCase.expected)
    }

    private fun readUnitTestCase(name: String): TestCase {
        val filename = "$name.toml"
        val testCasePath = Path(testDataPath, "unit", filename)
        if (!(testCasePath.exists())) {
            error("Unit test case '$filename' not found")
        }
        val mapper = TomlMapper()
        val testCase = mapper.readValue(testCasePath.toFile(), TestCase::class.java)
        return testCase
    }
}

abstract class IntegrationTestFixture : TestFixture() {
    fun runIntegrationTest(name: String) {
        val testCase = readIntegrationTestCase(name)

        runBlocking {
            runProjectsTestCase(name, testCase).onFailure { exception ->
                log.error(
                    "Test case '$name' failed: ${exception.message}, cause: ${exception.cause}, stack: ${exception.stackTraceToString()}",
                    exception
                )
                fail("Test case '$name' failed: ${exception.message}")
            }
        }
    }

    suspend fun runProjectsTestCase(name: String, testCase: ProjectTestCases): Result<Unit> = runCatching {
        val project = HeavyCargoProject(project, myFixture, testDataPath, testRootDisposable)

        project.setupProject(name)

        val projectUsages = project.collectProjectUsages(testCase).getOrNull() ?: error("No project usages found")

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
                val exception = result.exceptionOrNull()
                val message = "Test case '${testPath}' failed: ${exception?.message}. Found usages at: $foundPos"
                return Result.failure(RuntimeException(message))
            }
        }

        Result.success(Unit)
    }

    private fun readIntegrationTestCase(name: String): ProjectTestCases {
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
}

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

    fun checkExpectedUsages(
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
}
