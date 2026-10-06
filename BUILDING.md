# Build Open Horizon

JDK21, Android SDK37, NDK29.0.14206865, CMake3.22.1. Set ANDROID_HOME or local.properties. Run ./gradlew :app:assembleDebug (gradlew.bat on Windows). ARM64 only, Android12+.

CMake downloads IFM llama.cpp commit42adf019f76013dac873b5b43950d54d5ab27216 and verifies archive SHA256 c58cab48ce95510c65ed7f7abe20a3c70c5a0874908dd48268daa552d348aabb. Network is needed for uncached native source and Gradle dependencies. CPU is the supported/default backend; experimental Vulkan is disabled.

For release signing set HORIZON_KEYSTORE, HORIZON_STORE_PASSWORD, HORIZON_KEY_ALIAS and HORIZON_KEY_PASSWORD, then run :app:assembleRelease. Without those variables, output is unsigned. Never commit keys/passwords. Public package com.xyether.horizon.mnn is retained despite engine change to preserve upgrade compatibility. Continue using the same release key. Debug uses .debug.

Models download separately and are not bundled. The GGUF release retains existing workspace paths; old MNN models are not converted, loaded or automatically deleted. Download K2Q5 separately. No phone-data clearing or connected Gradle tests on real saved devices.
