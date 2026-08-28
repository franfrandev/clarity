import com.intellij.openapi.diagnostic.Logger

val log = Logger.getInstance("com.github.clarity.rust.test")

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

    fun testRustUnitEnumVariants() {
        runUnitTest("enum_variants")
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
