pluginManagement {
	repositories {
		maven { url = uri("https://repo.spring.io/snapshot") }
		gradlePluginPortal()
	}
}
rootProject.name = "FileServer"

dependencyResolutionManagement {
	versionCatalogs {
		create("libs") { from(files("../gradle/libs.versions.toml")) }
	}
}
