import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "com.chillygeek.hermesglasses"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.chillygeek.hermesglasses"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    // Bridge server on the LAN box that runs Hermes. Override per build with
    // -PbridgeUrl=... or by editing local.properties.
    val props = Properties().apply {
      val f = rootDir.resolve("local.properties")
      if (f.exists()) f.inputStream().use { load(it) }
    }
    buildConfigField(
      "String",
      "BRIDGE_URL",
      "\"${props.getProperty("bridgeUrl", "http://192.168.1.3:8790")}\"",
    )

    // Meta Wearables Device Access Toolkit setup. In Developer Mode both values
    // may be left at "0" and the app registers through the Meta AI app.
    manifestPlaceholders["mwdat_application_id"] =
      props.getProperty("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] =
      props.getProperty("mwdat_client_token", "0")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)

  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
}
