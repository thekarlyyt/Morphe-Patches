import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.gradle.api.tasks.compile.JavaCompile

group = "app.tkprotect"

patches {
    about {
        name = "TKProtect for YouTube Morphe"
        description = "Local spam and scam hiding panel for YouTube Morphe"
        source = "github"
        author = "TKProtect"
        contact = ""
        website = ""
        license = "GPLv3"
    }
}

// ---------------------------------------------------------------------------------------------
// Extensions
//
// Every folder  src/extension/java/app/tkprotect/patches/<app>/extension/
// is compiled and dexed into  extensions/<app>.dex  inside the .mpp.
// A patch loads it with  extendWith("extensions/<app>.dex").
// ---------------------------------------------------------------------------------------------

// android.jar is only used as compile/dex library stubs. Resolved from (in order):
// -PandroidJar=..., ANDROID_HOME, ANDROID_SDK_ROOT, the default macOS SDK location.
val androidJar: File = run {
    val explicit = providers.gradleProperty("androidJar").orNull
    if (explicit != null) return@run file(explicit)
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    file("$sdkRoot/platforms/android-36/android.jar")
}

val extensionPackageDir = "app/tkprotect/patches"
val extensionSourceRoot = file("src/extension/java")
val extensionDexOutput = layout.buildDirectory.dir("generated/extension-dex")

sourceSets {
    create("extension") {
        java.srcDir(extensionSourceRoot)
    }
    named("main") {
        resources.srcDir(extensionDexOutput)
    }
}

tasks.named<JavaCompile>("compileExtensionJava") {
    classpath += files(androidJar)
    doFirst {
        if (!androidJar.exists()) {
            throw GradleException(
                "android.jar not found at $androidJar. Set ANDROID_HOME or pass -PandroidJar=/path/to/android.jar",
            )
        }
    }
}

val dexExtensions = tasks.register("dexExtensions") {
    description = "Dexes each <app>/extension folder into extensions/<app>.dex."
    group = "build"

    val compileTask = tasks.named<JavaCompile>("compileExtensionJava")
    dependsOn(compileTask)

    val classesDir = compileTask.flatMap { it.destinationDirectory }
    inputs.dir(classesDir).optional()
    inputs.file(androidJar).optional()
    outputs.dir(extensionDexOutput)

    doLast {
        val outRoot = extensionDexOutput.get().asFile
        val extensionsDir = outRoot.resolve("extensions")
        outRoot.deleteRecursively()
        extensionsDir.mkdirs()

        val appsRoot = classesDir.get().asFile.resolve(extensionPackageDir)
        val appDirs = appsRoot.listFiles()
            ?.filter { it.resolve("extension").isDirectory }
            ?.sortedBy { it.name }
            .orEmpty()

        appDirs.forEach { appDir ->
            val classFiles = appDir.resolve("extension")
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.toPath() }
                .toList()
            if (classFiles.isEmpty()) return@forEach

            val workDir = temporaryDir.resolve(appDir.name).apply {
                deleteRecursively()
                mkdirs()
            }

            D8.run(
                D8Command.builder()
                    .addProgramFiles(classFiles)
                    .addLibraryFiles(androidJar.toPath())
                    .setMinApiLevel(28)
                    .setMode(CompilationMode.RELEASE)
                    .setOutput(workDir.toPath(), OutputMode.DexIndexed)
                    .build(),
            )

            val dex = workDir.resolve("classes.dex")
            check(dex.exists()) { "D8 produced no classes.dex for extension '${appDir.name}'" }
            dex.copyTo(extensionsDir.resolve("${appDir.name}.dex"), overwrite = true)
            logger.lifecycle("Extension: extensions/${appDir.name}.dex (${classFiles.size} classes)")
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(11)
    }

    processResources {
        dependsOn(dexExtensions)
    }

    named("sourcesJar") {
        dependsOn(dexExtensions)
    }
}
