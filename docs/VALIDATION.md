# Release validation — 2026-10-06



Release APK0.2.0/code20 rebuilt with Open Horizon branding. The29 focused tests below passed before the branding-only rename; no functional changes were made after them. ARM64 only, Android12+, package com.xyether.horizon.mnn, non-debuggable. APK signature verified with Android apksigner; dedicated release certificate is recorded below.



Focused JVM checks: **29 tests in 8 suites, zero failures/errors**. Suites: com.androidharness.app.local.HorizonLocalOnlyTest, com.androidharness.app.local.K2TokenizerTest, com.androidharness.app.local.K2XmlProtocolTest, com.androidharness.app.local.LocalToolLoopGuardTest, com.androidharness.app.local.MnnBundleStoreTest, com.androidharness.app.local.ResumableDownloadsTest, com.androidharness.app.tools.LocalBrowserBoundaryTest, com.androidharness.app.workspace.AgentFolderBoundaryTest. These check original K2 tokenizer/protocol, local-only provider behavior, model bundle storage/resume, loop guards and browser/file boundaries. They are not a full-suite result or new physical-device inference test.



K2 is the default provider and first model card; existing saved selections are not overwritten. Website startup suggestion removed. Signed APK contains MNN native runtime; model weights are downloaded separately. Historical0.1.2 MNN device tests passed on S24 Ultra with original K2 input/output tokenizer. This0.2.0 public package has not been installed/tested on a physical phone in this release task.



The existing release lint gate is disabled because the prior toolchain JavaDoc parser failed; no clean full lint/security audit is claimed. Complex voxel HTML generation remains unproven. Public uploads exclude keys, weights, caches, device logs and credentials.



```

Verifies

Verified using v1 scheme (JAR signing): false

Verified using v2 scheme (APK Signature Scheme v2): true

Verified using v3 scheme (APK Signature Scheme v3): false

Verified using v3.1 scheme (APK Signature Scheme v3.1): false

Verified using v4 scheme (APK Signature Scheme v4): false

Verified for SourceStamp: false

Number of signers: 1

Signer #1 certificate DN: CN=Open Horizon, O=XYETHER

Signer #1 certificate SHA-256 digest: 07367cd817ff7eb529520108739746f168dc508b647861e7ae8c0e66326172f9

Signer #1 certificate SHA-1 digest: 4c62e48c154c544bb9291ad9ca7286e12f1e119f

Signer #1 certificate MD5 digest: 36ffda372ebc48de327b4379986e4457

Signer #1 key algorithm: RSA

Signer #1 key size (bits): 4096

Signer #1 public key SHA-256 digest: fee267b8c0e0dcf01e4b35e1fb235e0b225c1ceb03bad4663295aec9d02771c9

Signer #1 public key SHA-1 digest: 8d4504410b51ccbcddbca9aa9cff059989c73eac

Signer #1 public key MD5 digest: c2b40cdb055a1885a4a5fdccf1047ca4



```

