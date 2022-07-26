import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

plugins {
  kotlin("multiplatform")
  kotlin("native.cocoapods")
}

kotlin {
  ios()

  cocoapods {
    noPodspec()
  }

  sourceSets {
    commonMain {
      dependencies {
        implementation(projects.zipline)
        implementation(projects.samples.emojiSearch.presenters)
        implementation(Dependencies.okio) // TODO this is presenters api so why do we need it here?
      }
    }
  }
}

dependencies {
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, projects.ziplineKotlinPlugin)
}
