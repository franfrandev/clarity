import com.intellij.openapi.diagnostic.Logger

val log = Logger.getInstance("com.github.filteroutrusttests")

class RustTestsUsageFilteringRuleUnitTests : UnitTestFixture() {
    fun testRustUnitCfgTest() {
        runUnitTest("cfg_test")
    }

    fun testRustUnitCfgUnitTest() {
        runUnitTest("cfg_unit_test")
    }

    fun testRustUnitGenericCfg() {
        runUnitTest("generic_cfg")
    }

    fun testRustUnitRecursive() {
        runUnitTest("recursive")
    }

    fun testRustUnitUnitTest() {
        runUnitTest("unit_test")
    }
}

class RustTestsUsageFilteringRuleIntegrationTest : IntegrationTestFixture() {
    fun testRustIntegrationBench() {
        runIntegrationTest("bench")
    }

    fun testRustIntegrationSimple() {
        runIntegrationTest("simple")
    }

    fun testRustIntegrationTrait() {
        runIntegrationTest("trait")
    }
}
