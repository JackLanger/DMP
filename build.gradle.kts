// Every subproject applying the Spring Boot plugin is treated as a server.
val serverRunTasks = provider {
    subprojects.filter { it.plugins.hasPlugin("org.springframework.boot") }
        .map { "${it.path}:bootRun" }
}

// Starts all servers in parallel: ./gradlew startServers --parallel
val startServers by tasks.registering {
    group = "application"
    description = "Starts all server subprojects."
    dependsOn(serverRunTasks)
}

// Building DMP starts all servers afterwards. Skip with -PnoServers.
tasks.register("build") {
    group = "build"
    description = "Builds all subprojects, then starts all servers."
    dependsOn(subprojects.map { "${it.path}:build" })
    if (!project.hasProperty("noServers")) finalizedBy(startServers)
}
