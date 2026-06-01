import org.gradle.kotlin.dsl.withType
import org.gradle.api.GradleException

allprojects {
    version = "1.0.4"
}

// =============================================================================
// .NET 10 SDK Management and Watchdog Build Tasks
// =============================================================================

val dotnetVersion = "10.0.200"
val dotnetInstallDir = layout.buildDirectory.dir("dotnet-sdk").get().asFile
val dotnetExecutable = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
    File(dotnetInstallDir, "dotnet.exe")
} else {
    File(dotnetInstallDir, "dotnet")
}

/**
 * Checks if .NET 10 SDK is available on the system or in our local installation
 */
fun isDotnetAvailable(): Boolean {
    return try {
        // First check system-wide installation
        val systemCheck = ProcessBuilder("dotnet", "--version")
            .redirectErrorStream(true)
            .start()
        systemCheck.waitFor()
        if (systemCheck.exitValue() == 0) {
            val version = systemCheck.inputStream.bufferedReader().readText().trim()
            logger.info("Found system .NET SDK version: $version")
            return version.startsWith("10.")
        }
        false
    } catch (_: Exception) {
        // Check our local installation
        if (dotnetExecutable.exists()) {
            try {
                val localCheck = ProcessBuilder(dotnetExecutable.absolutePath, "--version")
                    .redirectErrorStream(true)
                    .start()
                localCheck.waitFor()
                if (localCheck.exitValue() == 0) {
                    val version = localCheck.inputStream.bufferedReader().readText().trim()
                    logger.info("Found local .NET SDK version: $version")
                    return version.startsWith("10.")
                }
            } catch (localE: Exception) {
                logger.debug("Local .NET check failed: ${localE.message}")
            }
        }
        false
    }
}

/**
 * Downloads and installs .NET 10 SDK if not available
 */
tasks.register("ensureDotnetSdk") {
    group = "dotnet"
    description = "Ensures .NET 10 SDK is available, downloads if necessary"

    outputs.file(dotnetExecutable)
    
    doLast {
        if (isDotnetAvailable()) {
            logger.info(".NET 10 SDK is already available")
            return@doLast
        }
        
        logger.info("Downloading .NET 10 SDK...")
        dotnetInstallDir.mkdirs()
        
        val os = org.gradle.internal.os.OperatingSystem.current()
        val downloadUrl = when {
            os.isWindows -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-win-x64.zip"
            os.isMacOsX -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-osx-x64.tar.gz"
            os.isLinux -> "https://builds.dotnet.microsoft.com/dotnet/Sdk/$dotnetVersion/dotnet-sdk-$dotnetVersion-linux-x64.tar.gz"
            else -> {
                logger.error("Unsupported operating system: ${os.name}. Supported platforms: Windows, macOS, Linux")
            }
        }
        
        val downloadFile = File(temporaryDir, "dotnet-sdk.${if (os.isWindows) "zip" else "tar.gz"}")
        
        logger.info("Downloading from: $downloadUrl")
        ant.invokeMethod("get", mapOf(
            "src" to downloadUrl,
            "dest" to downloadFile,
            "verbose" to true
        ))
        
        logger.info("Extracting .NET SDK...")
        if (os.isWindows) {
            copy {
                from(zipTree(downloadFile))
                into(dotnetInstallDir)
            }
        } else if (os.isMacOsX || os.isLinux) {
            ProcessBuilder("tar", "-xzf", downloadFile.absolutePath, "-C", dotnetInstallDir.absolutePath, "--strip-components=0")
                .inheritIO()
                .start()
                .waitFor()
        } else {
            // This should not happen due to the check above, but just in case
            throw GradleException("Unsupported platform for extraction: ${os.name}")
        }
        
        // Make dotnet executable on Unix systems and set proper permissions
        if (!os.isWindows) {
            dotnetExecutable.setExecutable(true)
            // Also make sure all executables in the SDK are executable
            ProcessBuilder("find", dotnetInstallDir.absolutePath, "-name", "dotnet", "-type", "f", "-exec", "chmod", "+x", "{}", ";")
                .inheritIO()
                .start()
                .waitFor()
            ProcessBuilder("find", dotnetInstallDir.absolutePath, "-name", "*.dll", "-type", "f", "-exec", "chmod", "644", "{}", ";")
                .inheritIO()
                .start()
                .waitFor()
        }
        
        logger.info(".NET 10 SDK installed successfully at: ${dotnetInstallDir.absolutePath}")
    }
}

/**
 * Gets the dotnet command to use (prioritizes our local installation)
 */
fun getDotnetCommand(): String {
    // First check if we have a local installation
    if (dotnetExecutable.exists()) {
        try {
            val localCheck = ProcessBuilder(dotnetExecutable.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            localCheck.waitFor()
            if (localCheck.exitValue() == 0) {
                val version = localCheck.inputStream.bufferedReader().readText().trim()
                if (version.startsWith("10.")) {
                    logger.info("Using local .NET SDK: $version at ${dotnetExecutable.absolutePath}")
                    return dotnetExecutable.absolutePath
                }
            }
        } catch (e: Exception) {
            logger.debug("Local .NET check failed: ${e.message}")
        }
    }
    
    // Fall back to system installation only if local doesn't work
    return try {
        val systemCheck = ProcessBuilder("dotnet", "--version").start()
        systemCheck.waitFor()
        if (systemCheck.exitValue() == 0) {
            val version = systemCheck.inputStream.bufferedReader().readText().trim()
            if (version.startsWith("10.")) {
                logger.info("Using system .NET SDK: $version")
                return "dotnet"
            }
        }
        // If system version is not .NET 10, still prefer our local installation
        dotnetExecutable.absolutePath
    } catch (_: Exception) {
        dotnetExecutable.absolutePath
    }
}

/**
 * Builds the CDR Client Update Service (Windows Server 2019 only)
 */
tasks.register("buildUpdateService") {
    group = "dotnet"
    description = "Builds the CDR Client Update Service for Windows Server 2019"

    // Ensure .NET SDK is available if not present on system
    dependsOn("ensureDotnetSdk")

    inputs.files(fileTree(".") {
        include("**/*.cs", "**/*.csproj", "**/*.json")
    })
    outputs.dir("publish")

    doLast {
        logger.info("Building CDR Client Update Service (Windows-only, cross-platform build)")

        // Clean previous publish directory
        delete("publish")

        val dotnetCmd = getDotnetCommand()
        logger.info("Using dotnet command: $dotnetCmd")

        // Build using dotnet directly (works on all platforms)
        val processBuilder = ProcessBuilder(
            dotnetCmd,
            "publish",
            "-c", "Release",
            "-r", "win-x64",
            "--self-contained", "false",
            "-o", "publish"
        )
            .directory(file("."))
            .inheritIO()

        // Set DOTNET_ROOT if we're using our local installation
        if (dotnetCmd == dotnetExecutable.absolutePath) {
            processBuilder.environment().apply {
                put("DOTNET_ROOT", dotnetInstallDir.absolutePath)
                put("DOTNET_CLI_HOME", dotnetInstallDir.absolutePath)
            }
        }

        val process = processBuilder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Update Service build failed with exit code $exitCode")
        }

        // Copy installation and management scripts to publish folder
        logger.info("Copying installation scripts to publish folder")
        copy {
            from(".") {
                include("install-service.bat")
                include("uninstall-service.bat")
            }
            into("publish")
        }

        logger.info("Client Update Service built and published successfully")
    }
}

/**
 * Cleans the update service build artifacts
 */
tasks.register("cleanUpdateService") {
    group = "dotnet"
    description = "Cleans CDR Client Update Service build artifacts"

    doLast {
        delete("bin", "obj", "publish")
    }
}
