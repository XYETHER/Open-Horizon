# Building and signing

See README for the Android toolchain. Debug builds use your local Android development key. Public releases use package `com.xyether.horizon.mnn`; debug builds use `.debug` and store data separately.

For a signed release set `HORIZON_KEYSTORE` (absolute private keystore path), `HORIZON_STORE_PASSWORD`, `HORIZON_KEY_ALIAS`, and `HORIZON_KEY_PASSWORD`, then run `./gradlew :app:assembleRelease`. Without signing environment variables the release output is unsigned. Never commit keys or passwords. Keep and back up the same release key for every update.

The included native runtime is built from MNN commit `024a946b0b8fcf87c8a418229fadd4cd7858ffba`. See `scripts/rebuild-mnn.ps1` for Android CPU/ARM82/OpenCL/KleidiAI/low-memory flags. Rebuilding requires Git, Android SDK/NDK, CMake and upstream source access. The script writes only beneath your clone and replaces the included ARM64 library after a successful build. Existing library hashes/provenance remain in the SDK directory; update provenance if you rebuild or change dependencies.

Focused release checks and their results are recorded in `docs/VALIDATION.md`. Historical device measurements are not a promise of model quality or performance on other devices. Do not run connected Android Gradle tests against a phone containing data: the runner can uninstall its target.
