plugins {
    `java-library`
}

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    api(projects.plugin)
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("redis.clients:jedis:5.1.4")
}
