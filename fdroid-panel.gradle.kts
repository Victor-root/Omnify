// Sends the release APK to F-Droid Panel after each release build, except the ones Android Studio's
// Run button makes to install on a device. Another variant is sent instead when gradle.properties
// names it, a build type or a flavour and its build type:
//     fdroidPanel.variant=beta
//     fdroidPanel.variant=fossRelease
//
// Copy this file to the root folder of your Android project and add this line at the end of the
// build.gradle.kts of the app module (app/build.gradle.kts), not of the project:
//     apply(from = rootProject.file("fdroid-panel.gradle.kts"))
// and these lines, shown by the panel when you create an upload key, to local.properties or
// keystore.properties at the root of the project, files you keep out of version control:
//     fdroidPanel.url=https://fdroid.example.org
//     fdroidPanel.repository=1
//     fdroidPanel.key=fdp_...
// Without them, builds go on as before and nothing is sent. See docs/AUTOMATION.md.
//
// The task works with the configuration cache: the settings and the APK location are read while the
// build is configured, and the upload only uses those values.

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// Applied to the project instead of the app module, it would wait for a build that never happens there.
if (!plugins.hasPlugin("com.android.application")) {
    throw GradleException(
        "fdroid-panel.gradle.kts is applied to $path, which builds no app: apply it in app/build.gradle.kts",
    )
}

// The variant whose builds are sent, "release" unless gradle.properties names another.
val fdroidPanelVariant = providers.gradleProperty("fdroidPanel.variant").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "release"
val fdroidPanelAssemble = "assemble" + fdroidPanelVariant.replaceFirstChar { it.uppercase() }

tasks.register("uploadToFdroidPanel") {
    group = "publishing"
    description = "Sends the signed $fdroidPanelVariant APK to F-Droid Panel."

    val settingsFiles = listOf("local.properties", "keystore.properties")
    val settings = java.util.Properties()
    for (name in settingsFiles) {
        // Read through Gradle, so that changing the key invalidates the configuration cache.
        val text = providers.fileContents(rootProject.layout.projectDirectory.file(name)).asText.orNull
        if (text != null) settings.load(text.reader())
    }
    val settingsPlace = settingsFiles.joinToString(" or ")
    val url = settings.getProperty("fdroidPanel.url")?.trimEnd('/')
    val repository = settings.getProperty("fdroidPanel.repository")
    val key = settings.getProperty("fdroidPanel.key")
    val variant = fdroidPanelVariant
    // Where the build puts the APK, and where the "Generate Signed APK" wizard copies it: a folder per
    // flavour, then per build type, whose names spell the variant ("fossRelease" is in foss/release).
    val built = fileTree(layout.buildDirectory.dir("outputs/apk"))
    val copied = fileTree(layout.projectDirectory) { exclude("build/**") }
    val apkFolders = listOf(built, copied).map { tree ->
        tree.matching {
            include("**/*.apk")
            exclude("**/*-unsigned.apk")
        }
    }

    // Android Studio's Run button builds for the device it installs on and passes that device's
    // architecture; "Generate Signed APK" and command line builds target no device. A build for a
    // device is a test run, even of the release variant, so it is not sent.
    val forDevice = listOf("android.injected.build.abi", "android.injected.build.api")
        .any { providers.gradleProperty(it).isPresent }

    onlyIf("fdroidPanel.key is set in $settingsPlace") { key != null }
    onlyIf("not built by the Run button for a device") { !forDevice }
    doLast {
        if (url == null) throw GradleException("fdroidPanel.url is missing in $settingsPlace")
        if (repository == null) throw GradleException("fdroidPanel.repository is missing in $settingsPlace")
        val apks = mutableListOf<File>()
        for (tree in apkFolders) {
            tree.visit {
                val folders = relativePath.segments.dropLast(1)
                val spelled = folders.mapIndexed { index, folder ->
                    if (index == 0) folder else folder.replaceFirstChar { it.uppercase() }
                }.joinToString("")
                // A build type alone ("release") takes the APK of every flavour, as assembleRelease builds them.
                if (!isDirectory && (spelled == variant || folders.lastOrNull() == variant)) apks += file
            }
        }
        val apk = apks.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No signed $variant APK in build/outputs/apk or in the module's folder")

        val client = HttpClient.newHttpClient()
        val uploads = "$url/admin/api/repositories/$repository/uploads"
        // The status and the JSON answer of a request.
        fun send(path: String, configure: HttpRequest.Builder.() -> Unit): Pair<Int, Map<*, *>> {
            val request = HttpRequest.newBuilder(URI.create(path)).header("Authorization", "Bearer $key")
            request.configure()
            val response = try {
                client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            } catch (error: java.io.IOException) {
                throw GradleException("F-Droid Panel cannot be reached at $url: ${error.message ?: error}", error)
            }
            val body = response.body().takeIf { it.isNotBlank() }?.let { JsonSlurper().parseText(it) as? Map<*, *> }
            return response.statusCode() to (body ?: emptyMap<String, Any>())
        }
        fun failure(status: Int, body: Map<*, *>): GradleException {
            val error = body["error"] as? Map<*, *>
            val details = (error?.get("params") as? Map<*, *>)?.takeIf { it.isNotEmpty() } ?: ""
            return GradleException("F-Droid Panel answered $status: ${error?.get("code")} $details".trim())
        }
        fun call(path: String, configure: HttpRequest.Builder.() -> Unit): Map<*, *> {
            val (status, body) = send(path, configure)
            if (status >= 300) throw failure(status, body)
            return body
        }
        fun json(value: Any): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(value))

        val bytes = apk.readBytes()
        var upload = call(uploads) {
            header("Content-Type", "application/json")
            POST(json(mapOf("filename" to apk.name, "size" to bytes.size)))
        }
        val id = upload["id"]
        // A reverse proxy that refuses large requests (HTTP 413) makes the chunks smaller, as in the panel.
        var chunk = 4 * 1024 * 1024
        while (upload["status"] == "receiving") {
            val offset = (upload["received"] as Number).toInt()
            val (status, body) = send("$uploads/$id?offset=$offset") {
                header("Content-Type", "application/octet-stream")
                PUT(HttpRequest.BodyPublishers.ofByteArray(bytes, offset, minOf(chunk, bytes.size - offset)))
            }
            when {
                status == 413 && chunk > 256 * 1024 -> chunk /= 2
                status >= 300 -> throw failure(status, body)
                else -> upload = body
            }
        }

        val checks = upload["checks"] as? Map<*, *>
        for (warning in checks?.get("warnings") as? List<*> ?: emptyList<Any>()) {
            logger.warn("F-Droid Panel: ${(warning as Map<*, *>)["code"]} ${warning["params"]}")
        }
        if (upload["status"] != "ready") {
            call("$uploads/$id") { DELETE() }
            throw GradleException("F-Droid Panel refused ${apk.name}: ${checks?.get("blocking")}")
        }
        val added = call("$uploads/$id/add") {
            header("Content-Type", "application/json")
            POST(json(emptyMap<String, Any>()))
        }
        val published = if (added["publishJobId"] != null) ", publication started" else ""
        logger.lifecycle("F-Droid Panel: ${apk.name} added to ${added["packageName"]}$published")
    }
}

tasks.matching { it.name == fdroidPanelAssemble }.configureEach { finalizedBy("uploadToFdroidPanel") }

// A variant the module does not have would leave every build unsent without a word.
afterEvaluate {
    if (tasks.findByName(fdroidPanelAssemble) == null) {
        throw GradleException("fdroidPanel.variant=$fdroidPanelVariant: $path has no such variant")
    }
}
