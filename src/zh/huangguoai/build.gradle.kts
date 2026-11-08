import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "HuangguoAI"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "zh"
        baseUrl = "https://huangguoai.com"
    }
}

dependencies {
    implementation(project(":lib:dataimage"))
    implementation(project(":lib:m3u8server"))
}
