plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Networking (ktor 3.4.1 as in metrolist catalog)
    implementation("io.ktor:ktor-client-core:3.4.1")
    implementation("io.ktor:ktor-client-okhttp:3.4.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-client-encoding:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf:3.4.1")
    implementation("io.ktor:ktor-client-websockets:3.4.1")
    implementation("org.brotli:dec:0.1.2")
    // Dependency injection (koin 4.2.1 as in parent catalog)
    implementation(platform("io.insert-koin:koin-bom:4.2.1"))
    implementation("io.insert-koin:koin-core")
    // Others
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.0")
    implementation("co.touchlab:kermit:2.1.0")
}
