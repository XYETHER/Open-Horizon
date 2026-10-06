$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$mnnRoot=Join-Path $repoRoot 'native-work'
New-Item -ItemType Directory -Force $mnnRoot,"$mnnRoot\validation","$mnnRoot\vendor" | Out-Null
if (!(Test-Path "$mnnRoot\vendor\MNN")) { git clone https://github.com/alibaba/MNN.git "$mnnRoot\vendor\MNN"; if ($LASTEXITCODE) { throw 'Clone failed' } }
git -C "$mnnRoot\vendor\MNN" checkout 024a946b0b8fcf87c8a418229fadd4cd7858ffba
if ($LASTEXITCODE) { throw 'Pinned checkout failed' }
$sdkRoot=$env:ANDROID_HOME
if (!$sdkRoot) { throw 'Set ANDROID_HOME first' }
$cmakeTool=Join-Path $sdkRoot 'cmake\3.22.1\bin\cmake.exe'
$ninjaTool=Join-Path $sdkRoot 'cmake\3.22.1\bin\ninja.exe'
$nativeBuild=Join-Path $mnnRoot 'vendor\build-arm64'
$argsNative=@('-S',"$mnnRoot\vendor\MNN",'-B',$nativeBuild,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$ninjaTool", "-DCMAKE_TOOLCHAIN_FILE=$sdkRoot\ndk\29.0.14206865\build\cmake\android.toolchain.cmake",'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-31','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_CXX_FLAGS=-O3','-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384','-DMNN_BUILD_SHARED_LIBS=ON','-DMNN_SEP_BUILD=OFF','-DMNN_BUILD_LLM=ON','-DMNN_LLM_BUILD_DEMO=OFF','-DMNN_BUILD_FOR_ANDROID_COMMAND=ON','-DMNN_LOW_MEMORY=ON','-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON','-DMNN_ARM82=ON','-DMNN_KLEIDIAI=ON','-DMNN_KLEIDIAI_DEFAULT_ON=ON','-DMNN_OPENCL=ON','-DMNN_VULKAN=OFF','-DMNN_USE_THREAD_POOL=ON','-DMNN_USE_LOGCAT=ON','-DLLM_SUPPORT_HTTP_RESOURCE=OFF','-DMNN_BUILD_TEST=OFF','-DMNN_BUILD_CONVERTER=OFF')
& $cmakeTool @argsNative *> "$mnnRoot\validation\native-configure.log"
if($LASTEXITCODE -ne 0){Get-Content "$mnnRoot\validation\native-configure.log" -Tail 45;exit $LASTEXITCODE}
& $cmakeTool --build $nativeBuild --target MNN --parallel 10 *> "$mnnRoot\validation\native-build.log"
if($LASTEXITCODE -ne 0){Get-Content "$mnnRoot\validation\native-build.log" -Tail 55;exit $LASTEXITCODE}
Get-Content "$mnnRoot\validation\native-build.log" -Tail 5

Copy-Item -LiteralPath "$nativeBuild\libMNN.so" -Destination "$repoRoot\app\src\main\cpp\mnn-sdk\arm64-v8a\libMNN.so"
