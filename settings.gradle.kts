// Google's Maven repository is unreachable from some networks. `lectrice.googleMavenMirror=<url>` in the gitignored
// local.properties sends the Google groups to a mirror instead; gradle/verification-metadata.xml checks every file's
// signature and checksum whichever repository served it. Maven Central is reached through its repo1.maven.org host,
// which works where repo.maven.apache.org (mavenCentral()'s default) is blocked. (pluginManagement can't use
// anything declared outside it, hence the repeated lookup below.)
pluginManagement {
    val googleMirror = java.util.Properties().apply {
        settingsDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }.getProperty("lectrice.googleMavenMirror")
    val googleGroups = Action<RepositoryContentDescriptor> {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.google.*")
    }
    repositories {
        if (googleMirror == null) google { content(googleGroups) } else maven(googleMirror) { name = "GoogleMirror"; content(googleGroups) }
        mavenCentral { url = uri("https://repo1.maven.org/maven2/") }
        gradlePluginPortal()
    }
}

val googleMirror: String? = java.util.Properties().apply {
    settingsDir.resolve("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}.getProperty("lectrice.googleMavenMirror")
val googleGroups = Action<RepositoryContentDescriptor> {
    includeGroupByRegex("com\\.android.*")
    includeGroupByRegex("androidx.*")
    includeGroupByRegex("com\\.google.*")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (googleMirror == null) google { content(googleGroups) } else maven(googleMirror) { name = "GoogleMirror"; content(googleGroups) }
        mavenCentral { url = uri("https://repo1.maven.org/maven2/") }
    }
}

rootProject.name = "lectrice-android"
include(":spike")
