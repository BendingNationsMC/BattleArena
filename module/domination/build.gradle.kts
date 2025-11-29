repositories {
    maven(url = "https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    api(projects.plugin)
    compileOnly(files("$rootDir/plugin/libs/ProjectKorra-1.10.2.jar"))
    compileOnly("io.lumine:Mythic-Dist:5.6.1")
}
