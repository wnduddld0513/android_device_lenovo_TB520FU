# Unofficial LineageOS 24.0 for Lenovo Yoga Tab Plus (TB520FU)

Device tree for the Lenovo Yoga Tab Plus / YOGA Pad Pro (TB520FU). This is an
**unofficial** build: it is not supported or endorsed by
the LineageOS project, so report issues here, not to LineageOS.

| | |
|---|---|
| Model | TB520FU |
| Codename | TB520FU (Lenovo project name `lapis`, still used in the kernel and blob names) |
| SoC | Qualcomm Snapdragon 8 Gen 3 (SM8650, pineapple) |
| Kernel | 6.1.177 (Qualcomm msm-kernel with the Lenovo GPL changes), built from source |
| Stock firmware | ZUI `18.5.10.037_260929_ROW` |

## Repositories

All repositories are on [github.com/wnduddld0513](https://github.com/wnduddld0513),
branch `lineage-24.0`. The rest of the source is LineageOS 24.0 as published.

| Path | Repository | Contents |
|---|---|---|
| `device/lenovo/TB520FU` | [android_device_lenovo_TB520FU](https://github.com/wnduddld0513/android_device_lenovo_TB520FU) | device tree (this repository) |
| `device/lenovo/sm8650-common` | [android_device_lenovo_sm8650-common](https://github.com/wnduddld0513/android_device_lenovo_sm8650-common) | SM8650 platform tree |
| `kernel/lenovo/sm8650` | [android_kernel_lenovo_sm8650](https://github.com/wnduddld0513/android_kernel_lenovo_sm8650) | kernel |
| `kernel/lenovo/sm8650-modules` | [android_kernel_lenovo_sm8650-modules](https://github.com/wnduddld0513/android_kernel_lenovo_sm8650-modules) | vendor kernel modules |
| `kernel/lenovo/sm8650-devicetrees` | [android_kernel_lenovo_sm8650-devicetrees](https://github.com/wnduddld0513/android_kernel_lenovo_sm8650-devicetrees) | device trees (dtb/dtbo) |
| `vendor/lenovo/TB520FU` | [proprietary_vendor_lenovo_TB520FU](https://github.com/wnduddld0513/proprietary_vendor_lenovo_TB520FU) | device blobs (Git LFS) |
| `vendor/lenovo/sm8650-common` | [proprietary_vendor_lenovo_sm8650-common](https://github.com/wnduddld0513/proprietary_vendor_lenovo_sm8650-common) | platform blobs |

## Getting the source

Git LFS is needed for the vendor repository.

```bash
sudo apt install git-lfs && git lfs install
mkdir lineage && cd lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-24.0 --git-lfs
mkdir -p .repo/local_manifests
# save the manifest below as .repo/local_manifests/TB520FU.xml
repo sync -c -j$(nproc)
```

`.repo/local_manifests/TB520FU.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote name="wnduddld0513" fetch="https://github.com/wnduddld0513" revision="lineage-24.0" />

  <project name="android_device_lenovo_TB520FU" path="device/lenovo/TB520FU" remote="wnduddld0513" />
  <project name="android_device_lenovo_sm8650-common" path="device/lenovo/sm8650-common" remote="wnduddld0513" />
  <project name="android_kernel_lenovo_sm8650" path="kernel/lenovo/sm8650" remote="wnduddld0513" />
  <project name="android_kernel_lenovo_sm8650-modules" path="kernel/lenovo/sm8650-modules" remote="wnduddld0513" />
  <project name="android_kernel_lenovo_sm8650-devicetrees" path="kernel/lenovo/sm8650-devicetrees" remote="wnduddld0513" />
  <project name="proprietary_vendor_lenovo_TB520FU" path="vendor/lenovo/TB520FU" remote="wnduddld0513" />
  <project name="proprietary_vendor_lenovo_sm8650-common" path="vendor/lenovo/sm8650-common" remote="wnduddld0513" />
</manifest>
```

All seven repositories are listed because `lineage.dependencies` points to
the LineageOS organization, where they do not exist.

## Building

```bash
source build/envsetup.sh
breakfast TB520FU
m bacon
```

The package is `out/target/product/TB520FU/lineage-24.0-*-UNOFFICIAL-TB520FU.zip`.

The kernel, the vendor modules and the device trees are built from source by
the LineageOS kernel build (`vendor/lineage/build/tasks/kernel.mk`), the same
way as on the other SM8650 devices.

## Installing

Sideload the package from recovery (Apply update > Apply from ADB, then
`adb sideload <zip>`). It installs to the inactive slot, like any A/B update.

On a first install, or when the signing keys change, format data **before**
sideloading. Formatting afterwards also wipes the update snapshot in
`/metadata`, and the new slot does not boot.

## Credits

- **[LineageOS](https://github.com/LineageOS)**
  - [android_kernel_qcom_sm8650](https://github.com/LineageOS/android_kernel_qcom_sm8650),
    [android_kernel_qcom_sm8650-modules](https://github.com/LineageOS/android_kernel_qcom_sm8650-modules)
    and [android_kernel_qcom_sm8650-devicetrees](https://github.com/LineageOS/android_kernel_qcom_sm8650-devicetrees),
    the base of the kernel, the module and the device tree repositories.
  - [android_device_oneplus_caihong](https://github.com/LineageOS/android_device_oneplus_caihong),
    [android_device_oneplus_sm8650-common](https://github.com/LineageOS/android_device_oneplus_sm8650-common)
    and [android_kernel_oneplus_sm8650-modules](https://github.com/LineageOS/android_kernel_oneplus_sm8650-modules),
    the reference for the device tree layout and the external module build.
- **[Qualcomm / CodeLinaro](https://git.codelinaro.org/clo/la)**: msm-kernel and
  the camera driver (`CAMERA.LA.4.0.r2-07100-lanai.0`).
- **[Lenovo](https://support.lenovo.com/us/en/solutions/ht511330-lenovo-open-source-portal)**:
  the TB520FU kernel source release (16.1.02.078).
