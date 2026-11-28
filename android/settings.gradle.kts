pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        google()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}
rootProject.name = "UnipusHelperAndroid"
include(":app")
