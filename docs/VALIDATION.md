# GGUF0.2.1 validation — 2026-10-06

Signed release build successful. **35 focused JVM tests / 7 suites / zero failures or errors**. Covers sole-model/default-provider/checksum contract, Q8/Q5 cache memory ordering, advisory RAM/trained-context limits, protocol, tool-loop guard, download resume and workspace/browser isolation.

Verified APK: Open Horizon label, version0.2.1/code21, ARM64, non-debuggable, unchanged com.xyether.horizon.mnn package and identical release certificate to0.2.0. APK contains libllama and the GGUF JNI bridge; no MNN library. Retained storage paths protect existing workspace data; old MNN weights are not automatically deleted or treated as GGUF.

First test pass exposed obsolete catalog/storage expectations, corrected before the passing final run. Full-suite/lint and physical-device generation on the Q5 model were not run in this update; do not claim new speed/quality results. Historical MNN numbers do not apply. Model metadata comes from pinned publisher API, not an arbitrary download URL.
