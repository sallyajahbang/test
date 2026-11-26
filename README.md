# Sally C++ Launcher

Kerangka launcher Minecraft Java untuk Android. Lapisan native (C++) memuat
`libjvm.so` dari JRE Android, membuat JVM, lalu memanggil `main()` game.

## Build
Push ke GitHub. Workflow `.github/workflows/build.yml` menghasilkan APK debug
yang bisa diunduh dari tab Actions (artifact `SallyCppLauncher-debug`).

## Pakai
1. Siapkan JRE Android (berisi `lib/libjvm.so` beserta library pendukungnya).
2. Siapkan `client.jar` dan semua library Minecraft di classpath.
3. Isi path di aplikasi lalu tekan Launch. Log native ada di logcat (tag `CppLauncher`), output game di tag `MCGame`.

## Batasan saat ini
- Renderer OpenGL (GL4ES / Zink / Vulkan) belum terintegrasi, jadi tampilan game belum tampil.
- Input dan surface belum diteruskan ke JVM.
- Hanya arm64-v8a.
- Satu JVM per proses.
