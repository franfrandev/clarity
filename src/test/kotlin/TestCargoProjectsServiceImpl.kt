import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import org.rust.cargo.CfgOptions
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.impl.CargoProjectImpl
import org.rust.cargo.project.model.impl.CargoProjectsServiceImpl
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.CargoWorkspace.*
import org.rust.cargo.project.workspace.CargoWorkspaceData
import org.rust.cargo.project.workspace.CargoWorkspaceData.Package
import org.rust.cargo.project.workspace.CargoWorkspaceData.Target
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.openapiext.pathAsPath
import java.nio.file.Paths

class TestCargoProjectsServiceImpl(project: Project, cs: CoroutineScope) : CargoProjectsServiceImpl(project, cs) {
    private fun testTarget(
        crateRootUrl: String,
        name: String,
        kind: TargetKind,
        doctest: Boolean = true,
    ): Target = Target(crateRootUrl, name, kind, Edition.EDITION_2021, doctest, emptyList())

    private fun collectTargets(root: VirtualFile, contentRoot: String, name: String): List<Target> {
        val targets = mutableListOf<Target>()

        val src = root.findChild("src")
        if (src != null) {
            if (src.findChild("lib.rs") != null) {
                targets += testTarget("$contentRoot/src/lib.rs", name, TargetKind.Lib(LibKind.LIB))
            }
            if (src.findChild("main.rs") != null) {
                targets += testTarget("$contentRoot/src/main.rs", name, TargetKind.Bin)
            }
            src.findChild("bin")?.children.orEmpty().filter { !it.isDirectory && it.extension == "rs" }.forEach {
                targets += testTarget(
                    "$contentRoot/src/bin/${it.name}", it.nameWithoutExtension, TargetKind.Bin
                )
            }
        }

        collectDirTargets(root, contentRoot, "tests", TargetKind.Test, targets)
        collectDirTargets(root, contentRoot, "benches", TargetKind.Bench, targets)

        root.findChild("examples")?.children.orEmpty().filter { !it.isDirectory && it.extension == "rs" }.forEach {
            targets += testTarget(
                "$contentRoot/examples/${it.name}", it.nameWithoutExtension, TargetKind.ExampleBin
            )
        }

        if (root.findChild("build.rs") != null) {
            targets += testTarget(
                "$contentRoot/build.rs", "build_script_build", TargetKind.CustomBuild, doctest = false
            )
        }

        return targets
    }

    private fun collectDirTargets(
        root: VirtualFile,
        contentRoot: String,
        dirName: String,
        kind: TargetKind,
        targets: MutableList<Target>,
    ) {
        val dir = root.findChild(dirName) ?: return
        for (child in dir.children) {
            if (!child.isDirectory && child.extension == "rs") {
                targets += testTarget("$contentRoot/$dirName/${child.name}", child.nameWithoutExtension, kind)
            } else if (child.isDirectory && child.findChild("main.rs") != null) {
                targets += testTarget("$contentRoot/$dirName/${child.name}/main.rs", child.name, kind)
            }
        }
    }

    fun testCargoPackage(root: VirtualFile, contentRoot: String, name: String) = Package(
        id = "$name 0.0.1",
        contentRootUrl = contentRoot,
        name = name,
        version = "0.0.1",
        targets = collectTargets(root, contentRoot, name),
        source = null,
        origin = PackageOrigin.WORKSPACE,
        edition = Edition.EDITION_2021,
        features = emptyMap(),
        enabledFeatures = emptySet(),
        cfgOptions = CfgOptions.EMPTY,
        env = emptyMap(),
        outDirUrl = null,
        categories = emptySet(),
    )

    fun createTestCargoWorkspace(root: VirtualFile): CargoWorkspace {
        val contentRoot = root.url
        val cargoToml = root.findChild("Cargo.toml") ?: error("Cargo.toml not found in $contentRoot")
        val packageName = cargoToml.contentsToByteArray().toString(Charsets.UTF_8)
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("name") && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
            ?: error("Package name not found in ${cargoToml.path}")
        val packages = listOf(testCargoPackage(root, contentRoot, packageName))
        return CargoWorkspace.deserialize(
            Paths.get("$contentRoot/workspace/Cargo.toml"),
            CargoWorkspaceData(packages, emptyMap(), emptyMap(), contentRoot),
        )
    }

    suspend fun createTestProject(rootDir: VirtualFile, ws: CargoWorkspace) {
        val manifest = rootDir.pathAsPath.resolve("Cargo.toml")
        val testProject = CargoProjectImpl(
            manifest,
            this,
            emptyMap(),
            ws,
            null,
            null,
            workspaceStatus = CargoProject.UpdateStatus.UpToDate,
            rustcInfoStatus = CargoProject.UpdateStatus.NeedsUpdate
        )
        testProject.setRootDir(rootDir)
        modifyProjects { _ ->
            listOf(testProject)
        }.await()
    }
}
