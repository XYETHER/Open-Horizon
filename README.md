# Open Horizon



A minimal Android app for running a local AI agent. Powered by **MNN** with **K2 Horizon 0.9B INT8** as the default model.



## Get started



1. Download the ARM64 APK from [Releases](https://github.com/XYETHER/Open-Horizon/releases) and install it.

2. Open **Local models**, download K2 Horizon 0.9B (about 1.24 GB), and select it.

3. Start with CPU, a 4K context, and the default settings. Chat, research a topic, or draft a file.



Requires Android 12+ and an ARM64 phone. Tested previously on a Snapdragon 8 Gen 3 Galaxy S24 Ultra; performance and memory use vary. Model weights download separately from [Hugging Face](https://huggingface.co/XYETHER/K2-Horizon-0.9B-MNN). MiniCPM5 2B is also optional.



## Features



- Local inference with CPU and optional Adreno OpenCL.

- Chat, deep research, coding, thinking/output budgets, context and resource controls.

- Web search and page reading, file creation and editing, offline HTML preview.

- Agent file tools restricted to one app-owned workspace.

- Resumable, checksum-verified model downloads; storage information and explicit model deletion.

- App updates retain models and chats when installed with the same package and signing key.



Web tools and model downloads require internet access. Chat inference stays on the device. Small-model tool use and generated code can fail; advanced 3D scene generation is experimental and has not passed our tests. MNN cache controls differ from GGUF: CPU supports INT8 or unquantized KV; GPU uses FP16. No NPU support is claimed.



## Build



Install JDK 21, Android SDK platform 37, build tools required by Gradle, NDK `29.0.14206865`, and CMake `3.22.1`. Set `ANDROID_HOME` or your untracked `local.properties`.



```sh

./gradlew :app:assembleDebug

```



On Windows use `gradlew.bat`. The APK is in `app/build/outputs/apk/debug/`. The pinned MNN ARM64 library, public headers, licenses and provenance are included; model weights and signing keys are excluded. See [BUILDING.md](BUILDING.md) for signed builds and native-library reproduction.



## License and credits



App source: [MIT](LICENSE), derived from [AndroidHarness](https://github.com/Sanuu7/AndroidHarness), retaining its copyright. MNN and K2 model: Apache-2.0. Additional dependency notices are in `app/src/main/assets/licenses/`; native MNN provenance is in `app/src/main/cpp/mnn-sdk/PROVENANCE.json`.



Read [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md). Updates are installed manually from Releases; this fork does not install upstream AndroidHarness APKs.

