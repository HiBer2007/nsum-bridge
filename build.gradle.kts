plugins {
    java
}

group = "com.hibernet.flfhmcl"
version = libs.versions.nsumBridge.get()

// ---------------------------------------------------------------------------
// 编译依赖：HMCL / Fabric / Mixin 取自 testbed/libraries（与运行时一致的固定集）；
// FLF API 的四个模块取自工作区 mods/*/build/libs（先跑 .\gradlew.bat buildAll）。
//
// 覆盖方式（命令行 -P 参数）：
//   -PflfLibrariesDir=<目录>   指定依赖目录，默认 testbed/libraries
//   -PflfApiDir=<目录>         指定 FLF API jar 目录，默认 mods
//   -PhmclVersion=<版本>       指定编译目标 HMCL，默认见 libs.versions.toml
//   -PhmclJar=<jar 路径>       直接指定 HMCL jar
// ---------------------------------------------------------------------------
val workspaceRoot: File = projectDir.parentFile.parentFile

val hmclVersion: String = providers.gradleProperty("hmclVersion")
    .getOrElse(libs.versions.hmcl.get())

val librariesDir: File = providers.gradleProperty("flfLibrariesDir")
    .map { file(it) }
    .getOrElse(workspaceRoot.resolve("testbed/libraries"))

val hmclJar: File = providers.gradleProperty("hmclJar")
    .map { file(it) }
    .getOrElse(workspaceRoot.resolve("testbed/HMCL-$hmclVersion.jar"))

val modsDir: File = providers.gradleProperty("flfApiDir")
    .map { file(it) }
    .getOrElse(workspaceRoot.resolve("mods"))

fun library(name: String): File = librariesDir.resolve(name)

fun apiJar(module: String, jar: String): File = modsDir.resolve("$module/build/libs/$jar")

val compileLibraries = files(
    library("fabric-loader-${libs.versions.fabricLoader.get()}.jar"),
    library("sponge-mixin-${libs.versions.spongeMixin.get()}.jar"),
    library("javafx-base-${libs.versions.javafx.get()}-win.jar"),
    library("javafx-graphics-${libs.versions.javafx.get()}-win.jar"),
    library("javafx-controls-${libs.versions.javafx.get()}-win.jar"),
    library("log4j-api-${libs.versions.log4j.get()}.jar"),
    library("gson-${libs.versions.gson.get()}.jar"),
    hmclJar,
)

val flfApiJars = files(
    apiJar("flf-api", "flf-api-${libs.versions.flfApi.get()}.jar"),
    apiJar("flf-api-modpack", "flf-api-modpack-${libs.versions.flfApiModpack.get()}.jar"),
    apiJar("flf-api-launch", "flf-api-launch-${libs.versions.flfApiLaunch.get()}.jar"),
    apiJar("flf-ui-api", "flf-ui-api-${libs.versions.flfUiApi.get()}.jar"),
)

dependencies {
    compileOnly(compileLibraries)
    compileOnly(flfApiJars)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "NSUM Bridge",
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val modVersion = project.version.toString()
    inputs.property("modVersion", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

/** 编译前检查依赖是否齐备，缺失时给出可操作提示。 */
val checkCompileLibraries by tasks.registering {
    val required = files(compileLibraries, flfApiJars)
    doLast {
        val missing = required.files.filterNot(File::exists)
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("缺少编译依赖：")
                    missing.forEach { appendLine("  - $it") }
                    appendLine("请先在工作区根执行 .\\gradlew.bat buildAll，或用 -PflfLibrariesDir= / -PflfApiDir= 指定目录。")
                }
            )
        }
    }
}

tasks.compileJava { dependsOn(checkCompileLibraries) }
