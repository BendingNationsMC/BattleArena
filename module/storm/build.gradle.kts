repositories {
    maven(url = "https://mvn.lumine.io/repository/maven-public/")

}

dependencies {
    api(projects.plugin)
    compileOnly("io.lumine:Mythic-Dist:5.6.1")
}
