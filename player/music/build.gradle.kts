plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "audio.own.player.music"

    testOptions {
        // Robolectric runs real ExoPlayer decks (with fake renderers) against MockWebServer.
        unitTests.isIncludeAndroidResources = true
    }
    // The test tones live with :player:core; the music tests play them too.
    sourceSets["test"].resources.directories += "../core/src/test/resources"
}

dependencies {
    api(project(":player:core"))
    // Track, the queue's repeat mode and the equalizer curve are saved as JSON (last queue,
    // settings), so they carry @Serializable.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}
