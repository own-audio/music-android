plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "audio.own.player.music"
}

dependencies {
    api(project(":player:core"))
    // Track, the queue's repeat mode and the equalizer curve are saved as JSON (last queue,
    // settings), so they carry @Serializable.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
