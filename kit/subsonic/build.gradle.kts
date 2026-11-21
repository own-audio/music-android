plugins {
    id("ownaudio.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Api: the client takes an OkHttpClient and hands out HttpUrls.
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Live tests read their server from the environment and are skipped without it; a change there
// has to rerun them. Only whether each variable is set goes into Gradle's inputs, never a value.
tasks.withType<Test>().configureEach {
    listOf("HOST", "USER", "PASSWORD").forEach { name ->
        inputs.property("live-$name", providers.environmentVariable("SUBSONIC_$name").map { "set" }.orElse(""))
    }
}
