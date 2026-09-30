import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Mesma chave de assinatura do app antigo (android/build.sh), guardada FORA do git em
// ~/android-build-tools. Sem ela o build ainda funciona, só que assinado com a chave de
// debug (serve pra testar, mas não instala por cima de uma versão assinada com a oficial).
val toolsDir = File(System.getProperty("user.home"), "android-build-tools")
val keystoreFile = File(toolsDir, "festa-sync.jks")
val keystorePassFile = File(toolsDir, "festa-sync-keystore-senha.txt")
val hasReleaseKey = keystoreFile.exists() && keystorePassFile.exists()

android {
    namespace = "dev.partykit.r0usis.festasync"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        // ".nativo" enquanto o app nativo ainda não tem tudo (voz, jogos): instala AO LADO do
        // app antigo em vez de substituir. Quando estiver completo, sai o sufixo e ele passa
        // a atualizar o antigo.
        applicationId = "dev.partykit.r0usis.festasync.nativo"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
        // WebRTC (chat de voz) traz código nativo pra cada tipo de processador: fica só com os
        // de celular de verdade (arm) + x86_64 (emulador, pra testar)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("festa") {
                val pass = keystorePassFile.readText().trim()
                storeFile = keystoreFile
                storePassword = pass
                keyAlias = "festasync"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("festa") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // o lint da lifecycle 2.9 (vem junto do player do YouTube) quebra com este AGP — só
    // desliga a checagem automática do build de release, não afeta o app
    lint { checkReleaseBuilds = false }
    // bibliotecas nativas (WebRTC) comprimidas dentro do APK: ~metade do tamanho pra baixar
    // (o Android descompacta uma vez ao instalar)
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // player oficial do YouTube (IFrame API) embrulhado numa View — o YouTube só deixa tocar
    // vídeo pelo player dele, então essa é a única parte "web" do app
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
    // WebRTC oficial do Google empacotado (org.webrtc) — chat de voz direto entre os aparelhos
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
