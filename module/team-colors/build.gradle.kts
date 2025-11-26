repositories {
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
}

dependencies {
    api(projects.plugin)
    compileOnly(files("$rootDir/plugin/libs/ProjectKorra-1.10.2.jar"))
    compileOnly("com.github.retrooper:packetevents-spigot:2.10.1")
    compileOnly("fr.skytasul:glowingentities:1.4.9")
}
